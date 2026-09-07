import os
import glob
import torch
import numpy as np
import matplotlib.pyplot as plt

# Import the model architecture from the training script
from train_tcn import TCNVelocityEstimator, FEATURES
import torch.ao.quantization as quant

def load_quantized_model(model_path):
    """Loads the strictly quantized INT8 TCN model for evaluation."""
    model = TCNVelocityEstimator(in_features=FEATURES)
    model.eval()
    model.fuse_model()
    model.qconfig = quant.get_default_qat_qconfig('qnnpack')
    quant.prepare_qat(model, inplace=True)
    quant.convert(model, inplace=True)
    
    # Load the INT8 weights safely
    model.load_state_dict(torch.load(model_path, map_location='cpu'))
    model.eval()
    return model

if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    model_path = os.path.join(base_dir, 'results', 'saved_models', 'tiny_tcn_qat_int8.pth')
    val_dir = os.path.join(base_dir, 'results', 'processed_data', 'val')
    
    if not os.path.exists(model_path):
        print(f"❌ Error: Model not found at {model_path}. Please complete training first.")
        exit(1)
        
    val_files = glob.glob(os.path.join(val_dir, '*.npz'))
    if not val_files:
        print(f"❌ Error: No validation files found in {val_dir}.")
        exit(1)
        
    # Pick the first validation file (Unseen Route)
    test_file = val_files[0]
    print(f"Validating Uncertainty on Unseen Sequence: {os.path.basename(test_file)}")
    
    # Load Tensors
    data = np.load(test_file)
    X = data['X']  # Shape: (N, 200, 12)
    Y = data['Y']  # Shape: (N,)
    
    print(f"Loaded {len(X)} windows. Running inference...")
    model = load_quantized_model(model_path)
    
    with torch.no_grad():
        X_tensor = torch.tensor(X, dtype=torch.float32)
        outputs = model(X_tensor)
        
    # Extract Dual-Node Outputs
    mu = outputs[:, 0].numpy()
    log_var = outputs[:, 1].numpy()
    
    # Convert log-variance to standard deviation for plotting confidence intervals
    variance = np.exp(log_var)
    std_dev = np.sqrt(variance)
    
    # --- Physical Noise Extraction ---
    # Channel 2 is 'acc_z' (Vertical Vibration / Potholes)
    # Channel 8 is 'jerk_x' (Forward Braking/Acceleration Spikes)
    acc_z_windows = X[:, :, 2]
    jerk_x_windows = X[:, :, 8]
    
    # The standard deviation WITHIN a 1-second window physically represents how "noisy" that specific second was
    bumpiness = np.std(acc_z_windows, axis=1)
    jerkiness = np.std(jerk_x_windows, axis=1)
    
    # --- Plotting ---
    print("Generating Validation Plot...")
    plt.figure(figsize=(14, 10))
    
    # Subplot 1: Speed Prediction & Uncertainty Cloud
    plt.subplot(2, 1, 1)
    time_axis = np.arange(len(Y))
    
    plt.plot(time_axis, Y, 'k--', label='Ground Truth Speed (Vehicle CAN)', linewidth=1.5)
    plt.plot(time_axis, mu, 'b-', label='Predicted Speed (TCN μ)', linewidth=1.5)
    
    # Shaded Aleatoric Uncertainty (95% Confidence Interval ≈ ±2σ)
    plt.fill_between(time_axis, mu - 2*std_dev, mu + 2*std_dev, color='blue', alpha=0.25, label='AI Uncertainty Cloud (±2σ)')
    
    plt.title(f"TCN Speed Prediction vs. Aleatoric Uncertainty [{os.path.basename(test_file)}]")
    plt.ylabel('Speed (km/h)')
    plt.legend(loc='upper right')
    plt.grid(True, alpha=0.3)
    
    # Subplot 2: Physical Sensor Noise vs. AI Predicted Variance
    plt.subplot(2, 1, 2)
    
    # Normalize the values between 0 and 1 strictly for visual comparison of spikes
    bump_norm = bumpiness / np.max(bumpiness)
    jerk_norm = jerkiness / np.max(jerkiness)
    var_norm = variance / np.max(variance)
    
    plt.plot(time_axis, bump_norm, 'r-', alpha=0.5, label='Physical Pothole/Vibration Noise (Acc-Z Std)')
    plt.plot(time_axis, jerk_norm, 'orange', alpha=0.5, label='Physical Braking/Jerk Noise (Jerk-X Std)')
    plt.plot(time_axis, var_norm, 'b-', linewidth=2, label='AI Predicted Log-Variance (Model output)')
    
    plt.title("Correlation Proof: AI Dynamically Reacts to Real-World Sensor Chaos")
    plt.xlabel('Window Index (Time)')
    plt.ylabel('Normalized Scale (0.0 - 1.0)')
    plt.legend(loc='upper right')
    plt.grid(True, alpha=0.3)
    
    plt.tight_layout()
    out_path = os.path.join(base_dir, 'results', 'variance_validation.png')
    plt.savefig(out_path, dpi=300)
    print(f"✅ Success! Validation Graph saved to: {out_path}")
