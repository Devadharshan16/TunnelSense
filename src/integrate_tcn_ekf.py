"""
TCN → EKF Integration Pipeline
================================
This script connects the trained TCN velocity estimator to the Error-State 
Extended Kalman Filter (EKF) from dr_pipeline.py. It runs on REAL IO-VNBD 
driving sessions to produce the final ISRO metric: position drift percentage 
during a simulated GPS outage.

Flow:
  1. Load a real IO-VNBD driving session (S-*.csv + V-*.csv)
  2. Extract GPS ground truth trajectory (lat/lon → local x/y)
  3. Feed IMU windows through the trained INT8 TCN → get AI velocity predictions
  4. Feed AI velocity + gyro heading into the EKF during a simulated GPS blackout
  5. Compare EKF-estimated position vs. GPS ground truth
  6. Calculate and report position drift percentage
"""
import os
import sys
import glob
import numpy as np
import pandas as pd
import torch
import torch.ao.quantization as quant
import joblib
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

# Import the model architecture
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from train_tcn import TCNVelocityEstimator, FEATURES

# --- Constants ---
WINDOW_SIZE_SEC = 1.0
STRIDE_SEC = 0.5
CUTOFF_HZ = 10.0
EARTH_R = 6371000.0  # Earth radius in meters

# --- EKF Parameters (matched to dr_pipeline.py) ---
ACC_BIAS_STD = 0.05
ACC_NOISE_STD = 0.06
ACC_BIAS_RW = 0.0008
GYRO_BIAS_STD = np.deg2rad(0.6)
GYRO_NOISE_STD = np.deg2rad(0.05)
GYRO_BIAS_RW = np.deg2rad(0.01)


def gps_to_local(lat, lon, lat_ref, lon_ref):
    """Convert GPS lat/lon to local x/y meters relative to a reference point."""
    x = (lon - lon_ref) * np.cos(np.radians(lat_ref)) * np.pi / 180.0 * EARTH_R
    y = (lat - lat_ref) * np.pi / 180.0 * EARTH_R
    return x, y


def load_driving_session(s_csv, v_csv):
    """Load and synchronize a real IO-VNBD driving session."""
    s_df = pd.read_csv(s_csv, encoding='latin1')
    v_df = pd.read_csv(v_csv, encoding='latin1')
    
    # --- Extract timestamps ---
    t_s = [c for c in s_df.columns if 'time' in c.lower()][0]
    t_v = [c for c in v_df.columns if 'time' in c.lower()][0]
    
    s_time = s_df[t_s].values
    v_time = v_df[t_v].values
    
    if 'ms' in t_s.lower():
        s_time = s_time / 1000.0
    if 'seconds' in t_v.lower():
        pass  # already in seconds
    
    s_time = s_time - s_time[0]
    v_time = v_time - v_time[0]
    
    # --- Extract IMU channels (strict mapping) ---
    imu = {}
    for c in s_df.columns:
        cl = c.lower()
        if 'accelerometer x' in cl: imu['acc_x'] = s_df[c].values
        elif 'accelerometer y' in cl: imu['acc_y'] = s_df[c].values
        elif 'accelerometer z' in cl: imu['acc_z'] = s_df[c].values
        elif 'gyroscope roll' in cl: imu['gyro_x'] = s_df[c].values
        elif 'gyroscope pitch' in cl: imu['gyro_y'] = s_df[c].values
        elif 'gyroscope yaw' in cl: imu['gyro_z'] = s_df[c].values
    
    # --- Extract GPS ground truth from Smartphone file ---
    gps_lat_col = [c for c in s_df.columns if 'latitude' in c.lower() and 'gps' in c.lower()]
    gps_lon_col = [c for c in s_df.columns if 'longitude' in c.lower() and 'gps' in c.lower()]
    gps_speed_col = [c for c in s_df.columns if 'speed' in c.lower() and 'gps' in c.lower()]
    gps_heading_col = [c for c in s_df.columns if 'orientation' in c.lower() and 'gps' in c.lower()]
    
    gps_lat = s_df[gps_lat_col[0]].values if gps_lat_col else None
    gps_lon = s_df[gps_lon_col[0]].values if gps_lon_col else None
    gps_speed = s_df[gps_speed_col[0]].values if gps_speed_col else None
    gps_heading = s_df[gps_heading_col[0]].values if gps_heading_col else None
    
    # --- Extract Vehicle ground truth speed ---
    v_speed_col = [c for c in v_df.columns if 'velocity' in c.lower() and 'km/hr' in c.lower() and 'vertical' not in c.lower()]
    if not v_speed_col:
        v_speed_col = [c for c in v_df.columns if 'speed' in c.lower() and 'vehicle' in c.lower()]
    v_speed = v_df[v_speed_col[0]].values if v_speed_col else None
    
    # Interpolate vehicle speed to smartphone timestamps
    if v_speed is not None:
        v_speed_interp = np.interp(s_time, v_time, v_speed)
    else:
        v_speed_interp = None
    
    # Convert GPS lat/lon to local x/y
    if gps_lat is not None and gps_lon is not None:
        # Filter out invalid GPS readings (0,0)
        valid = (gps_lat != 0) & (gps_lon != 0)
        if valid.sum() > 10:
            lat_ref = gps_lat[valid][0]
            lon_ref = gps_lon[valid][0]
            gps_x, gps_y = gps_to_local(gps_lat, gps_lon, lat_ref, lon_ref)
        else:
            gps_x = gps_y = np.zeros(len(gps_lat))
            lat_ref = lon_ref = 0
    else:
        gps_x = gps_y = np.zeros(len(s_time))
        lat_ref = lon_ref = 0

    # Calculate actual sampling rate
    diffs = np.diff(s_time)
    diffs = diffs[diffs > 0]
    dt = np.median(diffs)
    fs = 1.0 / dt
    
    return {
        'time': s_time,
        'dt': dt,
        'fs': fs,
        'imu': imu,
        'gps_x': gps_x,
        'gps_y': gps_y,
        'gps_lat': gps_lat,
        'gps_lon': gps_lon,
        'gps_speed': gps_speed,
        'gps_heading': gps_heading,
        'v_speed': v_speed_interp,
        'N': len(s_time)
    }


def predict_velocity_tcn(session, model, scaler):
    """
    Run the trained TCN model on the driving session's IMU data.
    Returns an array of AI-predicted velocities (km/h), one per sliding window.
    """
    from scipy.signal import butter, filtfilt
    
    # Build raw feature array (11 channels matching preprocess.py)
    acc_x = session['imu']['acc_x']
    acc_y = session['imu']['acc_y']
    acc_z = session['imu']['acc_z']
    gyro_x = session['imu']['gyro_x']
    gyro_y = session['imu']['gyro_y']
    gyro_z = session['imu']['gyro_z']
    
    # Engineered features
    acc_mag = np.sqrt(acc_x**2 + acc_y**2 + acc_z**2)
    gyro_mag = np.sqrt(gyro_x**2 + gyro_y**2 + gyro_z**2)
    jerk_x = np.gradient(acc_x)
    jerk_y = np.gradient(acc_y)
    jerk_z = np.gradient(acc_z)
    
    raw = np.column_stack([acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z,
                           acc_mag, gyro_mag, jerk_x, jerk_y, jerk_z])
    
    # Apply Butterworth filter (same as preprocess.py)
    fs = session['fs']
    nyq = 0.5 * fs
    safe_cutoff = min(CUTOFF_HZ, nyq * 0.99)
    normal_cutoff = safe_cutoff / nyq
    b, a = butter(4, normal_cutoff, btype='low', analog=False)
    filtered = filtfilt(b, a, raw, axis=0)
    
    # Normalize using the same global scaler from preprocessing
    normalized = scaler.transform(filtered)
    
    # Create sliding windows
    window_size = int(WINDOW_SIZE_SEC * fs)
    stride = int(STRIDE_SEC * fs)
    
    predictions = []
    window_times = []
    
    with torch.no_grad():
        for i in range(0, len(normalized) - window_size, stride):
            window = normalized[i : i + window_size]
            # Shape: (1, channels, sequence) for Conv1d
            x = torch.tensor(window.T, dtype=torch.float32).unsqueeze(0)
            output = model(x)
            pred_speed = output[0, 0].item()
            predictions.append(pred_speed)
            window_times.append(session['time'][i + window_size - 1])
    
    return np.array(predictions), np.array(window_times)


def ekf_with_tcn(session, tcn_velocities, tcn_times, gps_outage_start, gps_outage_end):
    """
    Run the 8-state Error-State EKF using:
    - TCN-predicted velocity as the speed aiding source
    - Gyroscope yaw for heading propagation
    - GPS position updates ONLY outside the outage window
    
    Returns estimated x/y trajectory.
    """
    N = session['N']
    dt = session['dt']
    n = 8  # state dimension: [x, y, heading, vx_b, vy_b, b_ax, b_ay, b_g]
    
    xk = np.zeros(n)
    P = np.diag([1, 1, np.deg2rad(2)**2, 0.5, 0.5, 
                 ACC_BIAS_STD**2, ACC_BIAS_STD**2, GYRO_BIAS_STD**2])
    
    Q = np.diag([0, 0, (GYRO_NOISE_STD*dt)**2, (ACC_NOISE_STD*dt)**2, (ACC_NOISE_STD*dt)**2,
                 (ACC_BIAS_RW*np.sqrt(dt))**2, (ACC_BIAS_RW*np.sqrt(dt))**2, 
                 (GYRO_BIAS_RW*np.sqrt(dt))**2])
    
    R_nhc = np.array([[0.02**2]])       # Non-Holonomic Constraint noise
    R_gps = np.diag([5.0**2, 5.0**2])   # GPS position measurement noise (5m accuracy)
    
    # Convert TCN velocity RMSE to measurement noise
    # Our TCN has RMSE ~28.8 km/h = ~8 m/s
    tcn_sigma = 8.0  # m/s
    R_vel = np.array([[tcn_sigma**2]])
    
    xs, ys = [session['gps_x'][0]], [session['gps_y'][0]]
    xk[0] = session['gps_x'][0]
    xk[1] = session['gps_y'][0]
    
    # Initialize heading from GPS if available
    if session['gps_heading'] is not None:
        valid_h = session['gps_heading'][session['gps_heading'] != 0]
        if len(valid_h) > 0:
            xk[2] = np.deg2rad(valid_h[0])
    
    # Build a lookup for TCN velocities by time
    tcn_vel_interp = np.interp(session['time'], tcn_times, tcn_velocities)
    # Convert km/h to m/s
    tcn_vel_ms = tcn_vel_interp / 3.6
    
    gps_update_period = max(1, int(1.0 / dt))  # GPS updates at ~1 Hz
    
    in_outage = False
    outage_start_pos = None
    
    for k in range(1, N):
        t = session['time'][k]
        
        # --- Prediction step ---
        gyro_meas = session['imu']['gyro_z'][k] - xk[7]  # Yaw rate - bias
        ax_meas = session['imu']['acc_x'][k] - xk[5]
        ay_meas = session['imu']['acc_y'][k] - xk[6]
        
        heading = xk[2] + gyro_meas * dt
        
        xk[3] += ax_meas * dt   # vx_body
        xk[4] += ay_meas * dt   # vy_body
        xk[0] += (xk[3]*np.cos(heading) - xk[4]*np.sin(heading)) * dt
        xk[1] += (xk[3]*np.sin(heading) + xk[4]*np.cos(heading)) * dt
        xk[2] = heading
        
        # Covariance propagation
        Fk = np.eye(n)
        Fk[0,2] = -(xk[3]*np.sin(heading)+xk[4]*np.cos(heading))*dt
        Fk[1,2] = (xk[3]*np.cos(heading)-xk[4]*np.sin(heading))*dt
        Fk[0,3] = np.cos(heading)*dt; Fk[0,4] = -np.sin(heading)*dt
        Fk[1,3] = np.sin(heading)*dt; Fk[1,4] = np.cos(heading)*dt
        Fk[3,5] = -dt; Fk[4,6] = -dt; Fk[2,7] = -dt
        P = Fk @ P @ Fk.T + Q
        
        # --- Non-Holonomic Constraint: lateral velocity ≈ 0 ---
        H = np.zeros((1, n)); H[0,4] = 1.0
        yk = np.array([0.0]) - np.array([xk[4]])
        S = H @ P @ H.T + R_nhc
        K = P @ H.T @ np.linalg.inv(S)
        xk = xk + (K @ yk).flatten()
        P = (np.eye(n) - K @ H) @ P
        
        # --- TCN Velocity Aiding (always active — this IS the AI) ---
        v_tcn = tcn_vel_ms[k]
        H = np.zeros((1, n)); H[0,3] = 1.0
        yk = np.array([v_tcn]) - np.array([xk[3]])
        S = H @ P @ H.T + R_vel
        K = P @ H.T @ np.linalg.inv(S)
        xk = xk + (K @ yk).flatten()
        P = (np.eye(n) - K @ H) @ P
        
        # --- GPS Position Update (DISABLED during outage) ---
        is_outage = (gps_outage_start <= t <= gps_outage_end)
        
        if is_outage and not in_outage:
            in_outage = True
            outage_start_pos = np.array([xk[0], xk[1]])
            print(f"  ⚠️  GPS OUTAGE STARTED at t={t:.1f}s")
            
        if not is_outage and in_outage:
            in_outage = False
            print(f"  ✅ GPS RESTORED at t={t:.1f}s")
        
        if not is_outage and (k % gps_update_period == 0):
            gps_x_k = session['gps_x'][k]
            gps_y_k = session['gps_y'][k]
            
            if not (gps_x_k == 0 and gps_y_k == 0):  # Skip invalid readings
                H = np.zeros((2, n))
                H[0,0] = 1.0; H[1,1] = 1.0
                yk = np.array([gps_x_k, gps_y_k]) - np.array([xk[0], xk[1]])
                S = H @ P @ H.T + R_gps
                K = P @ H.T @ np.linalg.inv(S)
                xk = xk + (K @ yk).flatten()
                P = (np.eye(n) - K @ H) @ P
        
        xs.append(xk[0])
        ys.append(xk[1])
    
    return np.array(xs), np.array(ys)


def main():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data_dir = os.path.join(base_dir, 'data', 'IO-VNBD', 'Synchronised V abd S datasets', 
                            'Categorised IOVNB Dataset')
    model_path = os.path.join(base_dir, 'results', 'saved_models', 'tiny_tcn_qat_int8.pth')
    scaler_path = os.path.join(base_dir, 'results', 'processed_data', 'global_scaler.pkl')
    
    # --- 1. Load the trained INT8 TCN model ---
    print("Loading INT8 Quantized TCN Model...")
    model = TCNVelocityEstimator(in_features=FEATURES)
    model.eval(); model.fuse_model()
    model.train()
    model.qconfig = quant.get_default_qat_qconfig('qnnpack')
    quant.prepare_qat(model, inplace=True)
    model.eval()
    quant.convert(model, inplace=True)
    model.load_state_dict(torch.load(model_path, map_location='cpu'))
    model.eval()
    
    # --- 2. Load the global scaler ---
    scaler = joblib.load(scaler_path)
    print("Global scaler loaded.\n")
    
    # --- 3. Find a long driving session to test on ---
    s_files = glob.glob(os.path.join(data_dir, '**', 'S-*.csv'), recursive=True)
    # Pick sessions from the TEST set (non-V files) for honest evaluation
    test_sessions = [f for f in s_files if '-V' not in os.path.basename(f)]
    
    if not test_sessions:
        print("No test sessions found!")
        return
    
    # Use the longest session for the most meaningful test
    best_file = None
    best_size = 0
    for f in test_sessions:
        size = os.path.getsize(f)
        if size > best_size:
            best_size = size
            best_file = f
    
    s_csv = best_file
    v_csv = s_csv.replace('S-', 'V-', 1)
    
    print(f"Selected test session: {os.path.basename(s_csv)}")
    print(f"File size: {best_size / 1024:.0f} KB\n")
    
    # --- 4. Load the driving session ---
    print("Loading driving session data...")
    session = load_driving_session(s_csv, v_csv)
    duration = session['time'][-1]
    print(f"  Duration: {duration:.1f} seconds")
    print(f"  Samples: {session['N']}")
    print(f"  Sampling Rate: {session['fs']:.1f} Hz\n")
    
    # --- 5. Run TCN inference ---
    print("Running TCN velocity prediction on full session...")
    tcn_vel, tcn_times = predict_velocity_tcn(session, model, scaler)
    print(f"  Generated {len(tcn_vel)} velocity predictions.\n")
    
    # --- 6. Define GPS outage window ---
    # Simulate a 60-second GPS blackout in the middle of the drive
    outage_duration = 60.0
    outage_start = duration * 0.3  # Start outage at 30% of the drive
    outage_end = outage_start + outage_duration
    
    if outage_end > duration * 0.9:
        outage_end = duration * 0.9
        outage_start = outage_end - outage_duration
    
    print(f"Simulating GPS outage: {outage_start:.1f}s → {outage_end:.1f}s ({outage_duration:.0f}s blackout)")
    print("Running EKF with TCN velocity aiding...\n")
    
    # --- 7. Run EKF ---
    ekf_x, ekf_y = ekf_with_tcn(session, tcn_vel, tcn_times, outage_start, outage_end)
    
    # --- 8. Calculate drift metrics ---
    # Find indices within the outage window
    outage_mask = (session['time'] >= outage_start) & (session['time'] <= outage_end)
    outage_indices = np.where(outage_mask)[0]
    
    if len(outage_indices) > 0:
        # Position error during outage
        err_during_outage = np.hypot(
            ekf_x[outage_indices] - session['gps_x'][outage_indices],
            ekf_y[outage_indices] - session['gps_y'][outage_indices]
        )
        
        # Distance actually travelled during outage (from GPS ground truth)
        dx = np.diff(session['gps_x'][outage_indices])
        dy = np.diff(session['gps_y'][outage_indices])
        dist_during_outage = np.sum(np.hypot(dx, dy))
        
        final_outage_error = err_during_outage[-1]
        max_outage_error = err_during_outage.max()
        
        if dist_during_outage > 0:
            drift_pct = 100.0 * final_outage_error / dist_during_outage
        else:
            drift_pct = 0.0
        
        print("\n" + "="*60)
        print("🏆 REAL-DATA TCN+EKF INTEGRATION RESULTS 🏆")
        print("="*60)
        print(f"  GPS Outage Duration:        {outage_duration:.0f} seconds")
        print(f"  Distance During Outage:     {dist_during_outage:.1f} meters")
        print(f"  Final Position Error:       {final_outage_error:.2f} meters")
        print(f"  Max Position Error:         {max_outage_error:.2f} meters")
        print(f"  Position Drift:             {drift_pct:.3f}%")
        print(f"  ISRO Benchmark (<10%):      {'✅ MET' if drift_pct < 10 else '❌ NOT MET'}")
        print("="*60)
    
    # --- 9. Generate trajectory plot ---
    print("\nGenerating trajectory comparison plot...")
    
    fig, axes = plt.subplots(1, 2, figsize=(18, 7))
    
    # Plot 1: Full trajectory comparison
    ax1 = axes[0]
    ax1.plot(session['gps_x'], session['gps_y'], 'b-', linewidth=2, label='GPS Ground Truth', alpha=0.7)
    ax1.plot(ekf_x, ekf_y, 'r--', linewidth=1.5, label='EKF + TCN (AI Dead Reckoning)')
    
    # Highlight the outage region
    if len(outage_indices) > 0:
        ax1.plot(ekf_x[outage_indices], ekf_y[outage_indices], 
                'orange', linewidth=3, label=f'During GPS Outage ({outage_duration:.0f}s)')
    
    ax1.set_xlabel('X Position (meters)')
    ax1.set_ylabel('Y Position (meters)')
    ax1.set_title('Trajectory: GPS vs AI Dead Reckoning')
    ax1.legend()
    ax1.grid(True)
    ax1.set_aspect('equal')
    
    # Plot 2: Position error over time
    ax2 = axes[1]
    full_error = np.hypot(ekf_x - session['gps_x'][:len(ekf_x)], 
                          ekf_y - session['gps_y'][:len(ekf_y)])
    ax2.plot(session['time'][:len(full_error)], full_error, 'r-', linewidth=1.5)
    ax2.axvspan(outage_start, outage_end, alpha=0.3, color='orange', label='GPS Outage Window')
    ax2.set_xlabel('Time (seconds)')
    ax2.set_ylabel('Position Error (meters)')
    ax2.set_title('Position Error Over Time')
    ax2.legend()
    ax2.grid(True)
    
    plt.tight_layout()
    
    plot_path = os.path.join(base_dir, 'results', 'tcn_ekf_integration.png')
    plt.savefig(plot_path, dpi=300, bbox_inches='tight')
    print(f"✅ Trajectory plot saved to: {plot_path}")
    
    # Save numeric results
    results = {
        'session': os.path.basename(s_csv),
        'duration_s': float(duration),
        'outage_duration_s': float(outage_duration),
        'distance_during_outage_m': float(dist_during_outage) if len(outage_indices) > 0 else 0,
        'final_position_error_m': float(final_outage_error) if len(outage_indices) > 0 else 0,
        'max_position_error_m': float(max_outage_error) if len(outage_indices) > 0 else 0,
        'drift_pct': float(drift_pct) if len(outage_indices) > 0 else 0,
        'isro_benchmark_met': bool(drift_pct < 10) if len(outage_indices) > 0 else False
    }
    
    import json
    results_path = os.path.join(base_dir, 'results', 'tcn_ekf_results.json')
    with open(results_path, 'w') as f:
        json.dump(results, f, indent=2)
    print(f"✅ Numeric results saved to: {results_path}")


if __name__ == "__main__":
    main()
