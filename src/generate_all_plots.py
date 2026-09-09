import os
import glob
import numpy as np
import onnxruntime as ort
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

def main():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data_dir = os.path.join(base_dir, 'results', 'processed_data')
    model_path = os.path.join(base_dir, 'results', 'saved_models', 'tiny_tcn.onnx')
    
    all_files = glob.glob(os.path.join(data_dir, '*.npz'))
    test_files = [f for f in all_files if '-V' not in os.path.basename(f)]
    
    X_test, Y_test = [], []
    for file in test_files:
        data = np.load(file)
        X_test.append(data['X'])
        Y_test.append(data['Y'])
    
    X_test = np.concatenate(X_test, axis=0)
    Y_test = np.concatenate(Y_test, axis=0)
    X_test = np.transpose(X_test, (0, 2, 1)).astype(np.float32)
    
    session = ort.InferenceSession(model_path)
    input_name = session.get_inputs()[0].name
    
    predictions = []
    for i in range(len(X_test)):
        x_input = X_test[i:i+1]
        output = session.run(None, {input_name: x_input})
        predictions.append(output[0][0, 0])

    predictions = np.array(predictions)
    truths = Y_test
    
    rmse = np.sqrt(np.mean((predictions - truths)**2))
    mae = np.mean(np.abs(predictions - truths))
    
    # 1. Evaluate Model Plot
    plt.figure(figsize=(14, 6))
    snippet_len = min(300, len(truths))
    plt.plot(truths[:snippet_len], label='True Speed (Vehicle Ground Truth)', color='blue', linewidth=2)
    plt.plot(predictions[:snippet_len], label='ONNX Predicted Speed', color='red', linestyle='dashed', linewidth=2)
    plt.title(f'ONNX Evaluation | RMSE: {rmse:.2f} | MAE: {mae:.2f}')
    plt.xlabel('Time Steps (Sliding Windows)')
    plt.ylabel('Velocity (km/h)')
    plt.legend()
    plt.grid(True)
    p1 = os.path.join(base_dir, 'results', 'speed_evaluation_onnx.png')
    plt.savefig(p1, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"Generated {p1}")

    # 2. Residual Analysis Plot
    residuals = predictions - truths
    plt.figure(figsize=(14, 6))
    plt.hist(residuals, bins=50, color='purple', alpha=0.7)
    plt.title('Residuals Histogram (Prediction - Truth)')
    plt.xlabel('Error (km/h)')
    plt.ylabel('Frequency')
    plt.grid(True)
    p2 = os.path.join(base_dir, 'results', 'residual_analysis_onnx.png')
    plt.savefig(p2, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"Generated {p2}")

if __name__ == '__main__':
    main()
