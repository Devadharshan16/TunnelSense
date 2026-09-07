import os
import sys
import torch
import torch.ao.quantization as quant

# Ensure we can import from train_tcn
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from train_tcn import TCNVelocityEstimator, FEATURES

class CleanTCNBlock(torch.nn.Module):
    def __init__(self, in_channels, out_channels, kernel_size, dilation):
        super().__init__()
        padding = (kernel_size - 1) * dilation // 2 
        self.conv = torch.nn.Conv1d(in_channels, out_channels, kernel_size, padding=padding, dilation=dilation)
        self.relu = torch.nn.ReLU()
    def forward(self, x):
        return self.relu(self.conv(x))

class CleanTCN(torch.nn.Module):
    def __init__(self, in_features):
        super().__init__()
        self.block1 = CleanTCNBlock(in_features, 64, 3, 1)
        self.block2 = CleanTCNBlock(64, 128, 3, 2)
        self.block3 = CleanTCNBlock(128, 256, 3, 4)
        self.block4 = CleanTCNBlock(256, 256, 3, 8)
        self.pool = torch.nn.AdaptiveAvgPool1d(1)
        self.fc1 = torch.nn.Linear(256, 128)
        self.relu_fc = torch.nn.ReLU()
        self.fc2 = torch.nn.Linear(128, 1)

    def forward(self, x):
        x = self.block1(x)
        x = self.block2(x)
        x = self.block3(x)
        x = self.block4(x)
        x = self.pool(x)
        x = x.squeeze(-1)
        x = self.relu_fc(self.fc1(x))
        x = self.fc2(x)
        return x

def main():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    model_path = os.path.join(base_dir, 'results', 'saved_models', 'tiny_tcn_qat_int8.pth')
    
    if not os.path.exists(model_path):
        print(f"Error: Model not found at {model_path}")
        return

    print("1. Reconstructing INT8 QAT PyTorch Model...")
    model = TCNVelocityEstimator(in_features=FEATURES)
    model.eval()
    model.fuse_model()
    model.train()  # prepare_qat strictly requires training mode
    model.qconfig = quant.get_default_qat_qconfig('qnnpack')
    quant.prepare_qat(model, inplace=True)
    model.eval()
    quant.convert(model, inplace=True)
    model.load_state_dict(torch.load(model_path, map_location='cpu'))

    print("2. Extracting INT8 Weights into Clean Float32 ONNX-Compatible Architecture...")
    float_model = CleanTCN(FEATURES)
    
    # Manually map and dequantize weights to bypass PyTorch ONNX bugs
    float_model.block1.conv.weight.data = model.block1.conv.weight().dequantize()
    float_model.block1.conv.bias.data = model.block1.conv.bias()
    
    float_model.block2.conv.weight.data = model.block2.conv.weight().dequantize()
    float_model.block2.conv.bias.data = model.block2.conv.bias()
    
    float_model.block3.conv.weight.data = model.block3.conv.weight().dequantize()
    float_model.block3.conv.bias.data = model.block3.conv.bias()
    
    float_model.block4.conv.weight.data = model.block4.conv.weight().dequantize()
    float_model.block4.conv.bias.data = model.block4.conv.bias()
    
    float_model.fc1.weight.data = model.fc1.weight().dequantize()
    float_model.fc1.bias.data = model.fc1.bias()
    
    float_model.fc2.weight.data = model.fc2.weight().dequantize()
    float_model.fc2.bias.data = model.fc2.bias()

    float_model.eval()

    print("3. Generating Dummy Input (1, 11, 10)...")
    dummy_input = torch.randn(1, FEATURES, 10)

    onnx_path = os.path.join(base_dir, 'results', 'saved_models', 'tiny_tcn.onnx')
    print(f"4. Attempting Flawless ONNX Export ({onnx_path})...")
    
    try:
        torch.onnx.export(
            float_model,
            dummy_input,
            onnx_path,
            export_params=True,
            opset_version=13,
            do_constant_folding=True,
            input_names=['imu_vibration_input'],
            output_names=['predicted_velocity']
        )
        print("[SUCCESS] ONNX Model successfully generated!")
        print("\nNote: Your friend can use this exact .onnx file directly, or run it through 'onnx2tf' to get TFLite!")
    except Exception as e:
        print(f"[FAILED] Export Error: {e}")

if __name__ == "__main__":
    main()
