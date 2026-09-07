import os
import glob
import numpy as np
import pandas as pd
import joblib
from scipy.signal import butter, filtfilt
from sklearn.preprocessing import StandardScaler

# Constants based on typical IMU/GNSS datasets
WINDOW_SIZE_SEC = 1.0
STRIDE_SEC = 0.5
CUTOFF_HZ = 10.0  # Low-pass filter cutoff frequency

def butter_lowpass_filter(data, cutoff, fs, order=4):
    """Applies a Butterworth low-pass filter to remove engine/road vibration noise."""
    nyq = 0.5 * fs
    # Ensure cutoff is strictly less than Nyquist frequency (fs/2) to prevent scipy ValueError
    safe_cutoff = min(cutoff, nyq * 0.99)
    normal_cutoff = safe_cutoff / nyq
    b, a = butter(order, normal_cutoff, btype='low', analog=False)
    return filtfilt(b, a, data, axis=0)

def synchronize_and_interpolate(s_df, v_df):
    """
    Matches the high-frequency Smartphone (S) data with the lower-frequency 
    Vehicle (V) ground truth data.
    """
    s_cols = [c for c in s_df.columns if 'time' in c.lower()]
    v_cols = [c for c in v_df.columns if 'time' in c.lower()]
    
    if not s_cols or not v_cols:
        raise ValueError("Could not find timestamp columns in CSVs.")
        
    t_s = s_cols[0]
    t_v = v_cols[0]

    # Convert timestamps to numeric relative seconds
    s_time_raw = s_df[t_s]
    v_time_raw = v_df[t_v]
    
    if 'ms' in t_s.lower():
        s_time_sec = s_time_raw / 1000.0
    else:
        s_time_sec = s_time_raw
        
    if 'ms' in t_v.lower():
        v_time_sec = v_time_raw / 1000.0
    else:
        v_time_sec = v_time_raw

    s_df['rel_time'] = s_time_sec - s_time_sec.iloc[0]
    v_df['rel_time'] = v_time_sec - v_time_sec.iloc[0]

    # Strict column mapping based on actual IO-VNBD schema
    s_mapped = {
        'acc_x': None, 'acc_y': None, 'acc_z': None,
        'gyro_x': None, 'gyro_y': None, 'gyro_z': None
    }
    
    for c in s_df.columns:
        c_lower = c.lower()
        if 'accelerometer x' in c_lower: s_mapped['acc_x'] = s_df[c]
        elif 'accelerometer y' in c_lower: s_mapped['acc_y'] = s_df[c]
        elif 'accelerometer z' in c_lower: s_mapped['acc_z'] = s_df[c]
        elif 'gyroscope roll' in c_lower: s_mapped['gyro_x'] = s_df[c]
        elif 'gyroscope pitch' in c_lower: s_mapped['gyro_y'] = s_df[c]
        elif 'gyroscope yaw' in c_lower: s_mapped['gyro_z'] = s_df[c]
        
    for k, v in s_mapped.items():
        if v is None:
            raise ValueError(f"CRITICAL: Missing required IMU channel: {k}")
    
    # Target label: Speed
    speed_col = [c for c in v_df.columns if 'velocity' in c.lower() and 'km/hr' in c.lower() and 'vertical' not in c.lower()]
    if not speed_col:
        speed_col = [c for c in v_df.columns if 'speed' in c.lower() and 'vehicle' in c.lower()]
    if not speed_col:
        raise ValueError("Could not find Vehicle Speed column.")
        
    v_speed = v_df[speed_col[0]]

    # Interpolate Vehicle speed to match exactly with IMU timestamps
    interpolated_speed = np.interp(s_df['rel_time'], v_df['rel_time'], v_speed)
    
    # Construct unified dataframe
    unified_df = pd.DataFrame(s_mapped)
    unified_df['speed'] = interpolated_speed
    unified_df['rel_time'] = s_df['rel_time']
    
    return unified_df

def engineer_features(df):
    """
    Iteration 6 Feature Engineering: Derive physics-informed channels from raw IMU data.
    Adds acceleration magnitude, gyro magnitude, jerk (rate of change), and 
    per-channel spectral energy — all of which generalize across vehicles better 
    than raw amplitudes.
    """
    acc_x, acc_y, acc_z = df['acc_x'].values, df['acc_y'].values, df['acc_z'].values
    gyro_x, gyro_y, gyro_z = df['gyro_x'].values, df['gyro_y'].values, df['gyro_z'].values
    
    # 1. Total Acceleration Magnitude (orientation-independent)
    acc_mag = np.sqrt(acc_x**2 + acc_y**2 + acc_z**2)
    
    # 2. Total Gyroscope Magnitude (orientation-independent)
    gyro_mag = np.sqrt(gyro_x**2 + gyro_y**2 + gyro_z**2)
    
    # 3. Jerk (derivative of acceleration) — captures braking/acceleration events
    jerk_x = np.gradient(acc_x)
    jerk_y = np.gradient(acc_y)
    jerk_z = np.gradient(acc_z)
    
    df['acc_mag'] = acc_mag
    df['gyro_mag'] = gyro_mag
    df['jerk_x'] = jerk_x
    df['jerk_y'] = jerk_y
    df['jerk_z'] = jerk_z
    
    return df

def create_windows(features, labels, window_size, stride):
    """Slices continuous time-series into fixed-size overlapping blocks."""
    X, Y = [], []
    for i in range(0, len(features) - window_size, stride):
        X.append(features[i : i + window_size])
        Y.append(labels[i + window_size - 1])
    return np.array(X), np.array(Y)

if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    candidate_paths = [
        os.path.join(base_dir, 'IO-VNBD', 'Synchronised V abd S datasets', 'Categorised IOVNB Dataset'),
        os.path.join(base_dir, 'data', 'IO-VNBD', 'Synchronised V abd S datasets', 'Categorised IOVNB Dataset')
    ]
    data_dir = next((p for p in candidate_paths if os.path.exists(p)), candidate_paths[0])
    output_dir = os.path.join(base_dir, 'results', 'processed_data')
    os.makedirs(output_dir, exist_ok=True)
    
    search_pattern = os.path.join(data_dir, '**', 'S-*.csv')
    s_files = glob.glob(search_pattern, recursive=True)
    
    if not s_files:
        print(f"No S-*.csv files found in candidate paths:\n  " + "\n  ".join(candidate_paths) + "\nPlease check the path.")
        exit(0)
        
    print(f"Found {len(s_files)} smartphone datasets. Beginning Pass 1 (Global Normalization)...")
    
    # Feature columns: 6 raw + 5 engineered = 11 total
    feature_cols = ['acc_x', 'acc_y', 'acc_z', 'gyro_x', 'gyro_y', 'gyro_z',
                    'acc_mag', 'gyro_mag', 'jerk_x', 'jerk_y', 'jerk_z']
    
    all_filtered_features = []
    valid_file_pairs = []
    
    # PASS 1: Extract all filtered data to fit a single dataset-wide scaler
    for s_csv in s_files:
        s_basename = os.path.basename(s_csv)
        v_basename = s_basename.replace('S-', 'V-', 1)
        v_csv = os.path.join(os.path.dirname(s_csv), v_basename)
        
        if os.path.exists(v_csv):
            try:
                s_df = pd.read_csv(s_csv, encoding='latin1')
                v_df = pd.read_csv(v_csv, encoding='latin1')
                
                t_s = [c for c in s_df.columns if 'time' in c.lower()][0]
                time_diffs = s_df[t_s].diff().dropna()
                median_diff = time_diffs.median()
                actual_fs = 1000.0 / median_diff if 'ms' in t_s.lower() else 1.0 / median_diff
                
                unified_df = synchronize_and_interpolate(s_df, v_df)
                
                # Iteration 6: Engineer derived features BEFORE filtering
                unified_df = engineer_features(unified_df)
                
                raw_features = unified_df[feature_cols].values
                filtered_features = butter_lowpass_filter(raw_features, CUTOFF_HZ, actual_fs)
                
                all_filtered_features.append(filtered_features)
                valid_file_pairs.append((s_csv, actual_fs, unified_df, filtered_features))
                
            except Exception as e:
                print(f"Error reading {s_basename}: {e}")
        else:
            print(f"Warning: Could not find matching vehicle data '{v_basename}'")
            
    if not all_filtered_features:
        print("No valid files processed.")
        exit(1)
        
    # Fit global scaler
    global_scaler = StandardScaler()
    global_scaler.fit(np.vstack(all_filtered_features))
    scaler_path = os.path.join(output_dir, 'global_scaler.pkl')
    joblib.dump(global_scaler, scaler_path)
    print(f"Global scaler fitted on {len(feature_cols)} features and saved to {scaler_path}")
    
    # PASS 2: Transform, window, and save
    print(f"\nBeginning Pass 2 (Transforming and Windowing with {len(feature_cols)} channels)...")
    for s_csv, actual_fs, unified_df, filtered_features in valid_file_pairs:
        s_basename = os.path.basename(s_csv)
        normalized_features = global_scaler.transform(filtered_features)
        
        samples_per_window = int(WINDOW_SIZE_SEC * actual_fs)
        stride_samples = int(STRIDE_SEC * actual_fs)
        labels = unified_df['speed'].values
        
        X, Y = create_windows(normalized_features, labels, samples_per_window, stride_samples)
        
        out_name = s_basename.replace('S-', 'Processed-').replace('.csv', '.npz')
        
        # Post-check validation: ensure no dead channels
        for i, col in enumerate(feature_cols):
            if X[..., i].std() < 1e-6:
                raise ValueError(f"CRITICAL WARNING: Channel {col} in {out_name} is dead (std < 1e-6).")
                
        out_path = os.path.join(output_dir, out_name)
        np.savez_compressed(out_path, X=X, Y=Y)
        print(f"Saved {out_name} (Shape: {X.shape})")
        
    print(f"\nPre-processing complete. All .npz tensor files saved to: {output_dir}")
