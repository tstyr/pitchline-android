#!/usr/bin/env python3
"""Build and verify the small ONNX graphs that reference the original MIT checkpoints."""
import argparse
import hashlib
import pathlib
import urllib.request

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnx import TensorProto, helper, numpy_helper
from torch.nn import functional as F

MODELS = {
    "standard": (
        "https://zenodo.org/records/3370486/files/vocals-c8df74a5.pth",
        "c8df74a528f7bd3be90e21e9ec734d43da3a8f9c58cbb95ac5c17b0ecb52b217",
    ),
    "high": (
        "https://zenodo.org/records/3370489/files/vocals-b62c91ce.pth",
        "b62c91cedbc7a066f1778ead5b5cecb377aa3a46a31af1cce7c5c8769339d083",
    ),
}


def convert(mode, workspace, destination):
    url, checksum = MODELS[mode]
    checkpoint = workspace / f"{mode}.pth"
    if not checkpoint.is_file():
        urllib.request.urlretrieve(url, checkpoint)
    source = checkpoint.read_bytes()
    if hashlib.sha256(source).hexdigest() != checksum:
        raise ValueError(f"Unexpected {mode} SHA-256")
    state = torch.load(checkpoint, weights_only=True, map_location="cpu")
    nodes, initializers = [], []

    def init(name, value):
        initializers.append(numpy_helper.from_array(np.array(value, dtype=np.int64), name))
        return name

    def weight(name):
        value = state[name].contiguous().numpy().astype("<f4", copy=False)
        data = value.tobytes()
        offset = source.find(data)
        if offset < 0:
            raise ValueError(f"No external data for {name}")
        tensor = TensorProto(name=name, dims=value.shape, data_type=TensorProto.FLOAT, data_location=TensorProto.EXTERNAL)
        for key, field in (("location", checkpoint.name), ("offset", offset), ("length", len(data))):
            tensor.external_data.add(key=key, value=str(field))
        initializers.append(tensor)
        return name

    def op(kind, output, *inputs, **attrs):
        nodes.append(helper.make_node(kind, inputs, [output], **attrs))
        return output

    def linear_norm(stage, x):
        linear = op("Gemm", f"linear{stage}", x, weight(f"fc{stage}.weight"), transB=1)
        parameters = ("weight", "bias", "running_mean", "running_var")
        return op("BatchNormalization", f"norm{stage}", linear, *(weight(f"bn{stage}.{p}") for p in parameters), epsilon=1e-5)

    cutoff = state["input_mean"].numel()
    axes = init("axis1", [1])
    cropped = op("Slice", "crop", "magnitude", init("from", [0]), init("to", [cutoff]), init("axis2", [2]))
    x = op("Add", "mean", cropped, weight("input_mean"))
    x = op("Mul", "scaled", x, weight("input_scale"))
    x = op("Reshape", "features", x, init("inputShape", [-1, 2 * cutoff]))
    skip = op("Tanh", "squash", linear_norm(1, x))
    x = op("Unsqueeze", "sequence", skip, axes)
    hidden = 256
    hidden_shape = init("lstmShape", [-1, 1, 2 * hidden])
    direction_axis = init("directionAxis", [0])
    gate_order = init("gateOrder", np.concatenate([np.arange(g * hidden, (g + 1) * hidden) for g in [0, 3, 1, 2]]))
    for layer in range(3):
        recurrent = []
        for kind in ["weight_ih", "weight_hh", "bias"]:
            directions = []
            for direction, suffix in enumerate(["", "_reverse"]):
                parts = ["bias_ih", "bias_hh"] if kind == "bias" else [kind]
                permuted = [
                    op("Gather", f"{part}_{layer}_{direction}_permuted", weight(f"lstm.{part}_l{layer}{suffix}"), gate_order, axis=0)
                    for part in parts
                ]
                name = f"{kind}_{layer}_{direction}"
                merged = op("Concat", f"{name}_merged", *permuted, axis=0) if len(parts) > 1 else permuted[0]
                directions.append(op("Unsqueeze", name, merged, direction_axis))
            recurrent.append(op("Concat", f"{kind}_{layer}", *directions, axis=0))
        x = op("LSTM", f"lstm{layer}", x, *recurrent, direction="bidirectional", hidden_size=hidden)
        x = op("Reshape", f"joined{layer}", x, hidden_shape)
    x = op("Squeeze", "flattened", x, axes)
    x = op("Concat", "lstmSkip", skip, x, axis=1)
    x = op("Relu", "dense", linear_norm(2, x))
    x = linear_norm(3, x)
    x = op("Reshape", "outputBins", x, init("outputShape", [-1, 2, 2049]))
    x = op("Mul", "outputScaled", x, weight("output_scale"))
    x = op("Add", "outputScaledMean", x, weight("output_mean"))
    x = op("Relu", "positive", x)
    op("Mul", "vocals", x, "magnitude")

    graph = helper.make_graph(
        nodes, f"Open-Unmix {mode}", [helper.make_tensor_value_info("magnitude", TensorProto.FLOAT, ["frames", 2, 2049])],
        [helper.make_tensor_value_info("vocals", TensorProto.FLOAT, ["frames", 2, 2049])], initializers,
    )
    model = helper.make_model(graph, ir_version=8, opset_imports=[helper.make_opsetid("", 13)], producer_name="Pitchline")
    model.doc_string = "MIT Open-Unmix vocal model. External tensor data stays in the SHA-256-verified official checkpoint."

    test_graph = workspace / f"{mode}.onnx"
    onnx.save(model, test_graph)
    onnx.checker.check_model(str(test_graph))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(test_graph), options, providers=["CPUExecutionProvider"])
    torch.set_num_threads(2)
    generator = np.random.default_rng(23)

    def pytorch_forward(mag):
        def norm(i, value):
            norm_state = [state[f"bn{i}.{key}"] for key in ("running_mean", "running_var", "weight", "bias")]
            return F.batch_norm(F.linear(value, state[f"fc{i}.weight"]), *norm_state)
        x = ((mag[..., :cutoff] + state["input_mean"]) * state["input_scale"]).reshape(len(mag), -1)
        x = torch.tanh(norm(1, x))
        lstm = torch.nn.LSTM(512, 256, 3, bidirectional=True).eval()
        lstm.load_state_dict({key[5:]: value for key, value in state.items() if key.startswith("lstm.")})
        output, _ = lstm(x.unsqueeze(1))
        x = torch.cat([x, output.squeeze(1)], dim=1)
        x = norm(3, norm(2, x).relu()).reshape(len(mag), 2, -1)
        return (x * state["output_scale"] + state["output_mean"]).relu() * mag

    with torch.inference_mode():
        for length in (9, 127):
            signal = generator.standard_normal((length, 2, 2049), dtype=np.float32) * 10
            magnitude = np.abs(signal)
            actual = session.run(["vocals"], {"magnitude": magnitude})[0]
            reference = pytorch_forward(torch.from_numpy(magnitude)).numpy()
            np.testing.assert_allclose(actual, reference, atol=8e-4, rtol=2e-4)
    (destination / test_graph.name).write_bytes(test_graph.read_bytes())
    print(f"{mode}: CPU ONNX matches PyTorch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("app/src/main/assets/models"))
    args = parser.parse_args()
    args.workspace.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    for mode in MODELS:
        convert(mode, args.workspace, args.output)


if __name__ == "__main__":
    main()
