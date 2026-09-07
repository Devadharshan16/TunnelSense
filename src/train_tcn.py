import os
import glob
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader
import torch.ao.quantization as quant
from torch.ao.quantization import QuantStub, DeQuantStub

# --- Hyperparameters ---
BATCH_SIZE = 64
EPOCHS = 80
LEARNING_RATE = 1e-3
FEATURES = 11  # 6 raw IMU + 5 engineered (acc_mag, gyro_mag, jerk_x/y/z)
EARLY_STOP_PATIENCE = 15  # Stop if val loss doesn't improve for 15 epochs

# --- Dataset Definition ---
class IMUDataset(Dataset):
    def __init__(self, file_list):
        self.X = []
        self.Y = []
        
        for file in file_list:
            try:
                data = np.load(file)
                self.X.append(data['X'])
                self.Y.append(data['Y'])
            except Exception as e:
                print(f"Skipping {file} due to error: {e}")
                
        if len(self.X) == 0:
            raise ValueError("No valid data found in the provided files.")
            
        self.X = np.concatenate(self.X, axis=0)
        self.Y = np.concatenate(self.Y, axis=0)
        
        # PyTorch Conv1d expects shape: (Batch, Channels, SequenceLength)
        # Currently X is (Batch, Sequence, Channels), so we transpose it
        self.X = np.transpose(self.X, (0, 2, 1))

    def __len__(self):
        return len(self.X)

    def __getitem__(self, idx):
        x = torch.tensor(self.X[idx], dtype=torch.float32)
        y = torch.tensor(self.Y[idx], dtype=torch.float32)
        return x, y

# --- QAT-Ready Model Architecture (Iteration 6: Wider + Deeper) ---
class TCNBlock(nn.Module):
    def __init__(self, in_channels, out_channels, kernel_size, dilation):
        super(TCNBlock, self).__init__()
        padding = (kernel_size - 1) * dilation // 2 
        self.conv = nn.Conv1d(in_channels, out_channels, kernel_size, 
                              padding=padding, dilation=dilation)
        self.bn = nn.BatchNorm1d(out_channels)
        self.relu = nn.ReLU()
        
    def forward(self, x):
        return self.relu(self.bn(self.conv(x)))
        
    def fuse_model(self):
        quant.fuse_modules(self, [['conv', 'bn', 'relu']], inplace=True)


class TCNVelocityEstimator(nn.Module):
    def __init__(self, in_features):
        super(TCNVelocityEstimator, self).__init__()
        self.quant = QuantStub()
        # Iteration 6: Wider channels (64→128→256→256) + 4th block
        self.block1 = TCNBlock(in_features, 64, kernel_size=3, dilation=1)
        self.block2 = TCNBlock(64, 128, kernel_size=3, dilation=2)
        self.block3 = TCNBlock(128, 256, kernel_size=3, dilation=4)
        self.block4 = TCNBlock(256, 256, kernel_size=3, dilation=8)
        self.pool = nn.AdaptiveAvgPool1d(1)
        
        self.fc1 = nn.Linear(256, 128)
        self.relu_fc = nn.ReLU()
        self.dropout = nn.Dropout(0.4)
        self.fc2 = nn.Linear(128, 1)
        self.dequant = DeQuantStub()

    def forward(self, x):
        x = self.quant(x)
        x = self.block1(x)
        x = self.block2(x)
        x = self.block3(x)
        x = self.block4(x)
        x = self.pool(x)
        x = x.squeeze(-1)
        x = self.fc1(x)
        x = self.relu_fc(x)
        x = self.dropout(x)
        x = self.fc2(x)
        x = self.dequant(x)
        return x

    def fuse_model(self):
        self.block1.fuse_model()
        self.block2.fuse_model()
        self.block3.fuse_model()
        self.block4.fuse_model()
        quant.fuse_modules(self, [['fc1', 'relu_fc']], inplace=True)


if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data_dir = os.path.join(base_dir, 'results', 'processed_data')
    model_save_dir = os.path.join(base_dir, 'results', 'saved_models')
    os.makedirs(model_save_dir, exist_ok=True)
    
    all_files = glob.glob(os.path.join(data_dir, '*.npz'))
    
    train_files = [f for f in all_files if '-V' in os.path.basename(f)]
    test_files = [f for f in all_files if '-V' not in os.path.basename(f)]
    
    print(f"Strict Split: Training on {len(train_files)} files (Route/Session Group 1).")
    print(f"Strict Split: Validating on {len(test_files)} unseen files (Route/Session Group 2).")
    
    train_dataset = IMUDataset(train_files)
    test_dataset = IMUDataset(test_files)
    
    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True, num_workers=0)
    test_loader = DataLoader(test_dataset, batch_size=BATCH_SIZE, shuffle=False, num_workers=0)
    
    # GPU Support: Use CUDA if available (RTX 4050), otherwise CPU
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Executing Quantization-Aware Training on: {device}")
    if device.type == "cuda":
        print(f"GPU Detected: {torch.cuda.get_device_name(0)}")
    
    model = TCNVelocityEstimator(in_features=FEATURES)
    
    # --- CORRECT QAT SEQUENCE ---
    model.eval()
    model.fuse_model() 
    
    model.train()
    model.qconfig = quant.get_default_qat_qconfig('qnnpack')
    quant.prepare_qat(model, inplace=True)
    
    # Move to GPU AFTER QAT preparation
    model = model.to(device)
    
    optimizer = optim.Adam(model.parameters(), lr=LEARNING_RATE, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.ReduceLROnPlateau(optimizer, mode='min', factor=0.5, patience=5)
    criterion = nn.MSELoss()
    
    # --- Early Stopping Setup ---
    best_val_loss = float('inf')
    patience_counter = 0
    best_model_state = None
    
    for epoch in range(EPOCHS):
        # 1. Training Phase
        model.train()
        total_train_loss = 0
        for batch_x, batch_y in train_loader:
            batch_x, batch_y = batch_x.to(device), batch_y.to(device)
            optimizer.zero_grad()
            output = model(batch_x)
            loss = criterion(output.squeeze(), batch_y)
            loss.backward()
            optimizer.step()
            total_train_loss += loss.item()
            
        avg_train_loss = total_train_loss / len(train_loader)
        
        # 2. Validation Phase (Unseen Routes/Sessions)
        model.eval()
        total_val_loss = 0
        with torch.no_grad():
            for batch_x, batch_y in test_loader:
                batch_x, batch_y = batch_x.to(device), batch_y.to(device)
                output = model(batch_x)
                loss = criterion(output.squeeze(), batch_y)
                total_val_loss += loss.item()
                
        avg_val_loss = total_val_loss / len(test_loader)
        
        scheduler.step(avg_val_loss)
        
        current_lr = optimizer.param_groups[0]['lr']
        
        # --- Early Stopping Check ---
        if avg_val_loss < best_val_loss:
            best_val_loss = avg_val_loss
            patience_counter = 0
            # Save best model weights (on CPU for QAT conversion later)
            best_model_state = {k: v.cpu().clone() for k, v in model.state_dict().items()}
            print(f"Epoch [{epoch+1}/{EPOCHS}] | LR: {current_lr:.6f} | Train Loss: {avg_train_loss:.4f} | Val Loss: {avg_val_loss:.4f} | ★ Best")
        else:
            patience_counter += 1
            print(f"Epoch [{epoch+1}/{EPOCHS}] | LR: {current_lr:.6f} | Train Loss: {avg_train_loss:.4f} | Val Loss: {avg_val_loss:.4f} | Patience: {patience_counter}/{EARLY_STOP_PATIENCE}")
            
        if patience_counter >= EARLY_STOP_PATIENCE:
            print(f"\n⚡ Early Stopping triggered at Epoch {epoch+1}. Best Val Loss: {best_val_loss:.4f}")
            break
        
    # CONVERT TO INT8 (must be on CPU)
    print("\nTraining finished. Converting best model to INT8...")
    
    # Rebuild a fresh model on CPU for INT8 conversion
    model_cpu = TCNVelocityEstimator(in_features=FEATURES)
    model_cpu.eval()
    model_cpu.fuse_model()
    model_cpu.train()
    model_cpu.qconfig = quant.get_default_qat_qconfig('qnnpack')
    quant.prepare_qat(model_cpu, inplace=True)
    
    # Load the best checkpoint weights
    model_cpu.load_state_dict(best_model_state)
    
    model_cpu.eval()
    quant.convert(model_cpu, inplace=True)
    
    save_path = os.path.join(model_save_dir, 'tiny_tcn_qat_int8.pth')
    torch.save(model_cpu.state_dict(), save_path)
    print(f"Success! Highly compressed INT8 model saved to {save_path}")
    print(f"Best Validation Loss achieved: {best_val_loss:.4f}")
