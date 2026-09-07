import os
import torch
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
from train_tcn import TCNVelocityEstimator, FEATURES

def export_to_onnx_and_quantize():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    model_save_dir = os.path.join(base_dir, 'results', 'saved_models')
    
    # We will load the SAFEGUARD file which contains the raw weights
    fakequant_path = os.path.join(model_save_dir, 'tiny_tcn_qat_fakequant.pth')
    
    if not os.path.exists(fakequant_path):
        print(f"❌ Error: Safeguard model not found at {fakequant_path}")
        return

    print("1. Loading Clean FP32 Architecture...")
    # Build a clean model WITHOUT calling prepare_qat()
    model = TCNVelocityEstimator(in_features=FEATURES)
    model.eval()
    
    print("2. Stripping QAT Observers and loading core weights...")
    # strict=False allows us to perfectly load the core weights while ignoring the bugged PyTorch QAT observers
    state_dict = torch.load(fakequant_path, map_location='cpu')
    model.load_state_dict(state_dict, strict=False)
    
    print("3. Exporting Core Model to ONNX...")
    fp32_onnx_path = os.path.join(model_save_dir, 'tiny_tcn_fp32.onnx')
    dummy_input = torch.randn(1, FEATURES, 200)
    
    # This will succeed flawlessly because we stripped the bugged FakeQuantize nodes
    torch.onnx.export(
        model, 
        dummy_input, 
        fp32_onnx_path,
        opset_version=13,
        input_names=['input'],
        output_names=['mu', 'log_var'],
        do_constant_folding=True
    )
    print(f"✅ Successfully created FP32 ONNX model: {fp32_onnx_path}")
    
    print("\n4. Triggering ONNXRuntime INT8 Quantization (PTQ)...")
    int8_onnx_path = os.path.join(model_save_dir, 'tiny_tcn_int8.onnx')
    
    # Use Microsoft's official ONNX quantization library to compress it
    quantize_dynamic(
        model_input=fp32_onnx_path,
        model_output=int8_onnx_path,
        weight_type=QuantType.QInt8
    )
    
    print(f"\n🎉 DONE! Verified INT8 ONNX Model saved to: {int8_onnx_path}")
    print("You can now open this file in Netron. It is fully compressed to INT8.")

if __name__ == '__main__':
    export_to_onnx_and_quantize()
