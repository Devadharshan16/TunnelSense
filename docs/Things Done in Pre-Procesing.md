### 1. Synchronization and Interpolation

  • What we are doing: We open the S-*.csv (Smartphone) and V-*.csv (Vehicle) files. Since the smartphone IMU records data rapidly (e.g., 100 times a second) but the vehicle OBD/GPS updates slowly (e.g., 1 to 10 times a second), their timestamps don't align. We map them to a unified timeline and mathematically interpolate the vehicle's speed to match the exact milliseconds the smartphone recorded a vibration.
  • Why we are doing it: A neural network requires exactly paired (Feature, Label) data. We cannot train the AI on an IMU vibration if we don't know the exact speed of the car at that specific millisecond.

  ### 2. Low-Pass Filtering

  • What we are doing: We pass the raw Accelerometer and Gyroscope columns through a 4th-order Butterworth low-pass filter (set to a ~10Hz cutoff). We apply it both forwards and backwards (filtfilt) to prevent time-shifting the data.
  • Why we are doing it: Smartphones are rarely bolted securely to a car. They sit in cup holders or mounts and pick up high-frequency noise from the engine humming, chassis rattling, and road texture (potholes). If we don't filter this out, the AI will try to predict velocity based on engine vibrations rather than the actual physical acceleration of the car.

  ### 3. Normalization (Z-Score Scaling)

  • What we are doing: We calculate the mean and standard deviation for every individual sensor axis (Acc X/Y/Z, Gyro X/Y/Z) across the dataset. We then subtract the mean and divide by the standard deviation.
  • Why we are doing it: Accelerometer values are measured in m/s² (typically between -9.8 and +9.8) while Gyroscopes measure in rad/s (usually very small decimals like 0.05). If we don't scale them so that they are mathematically on the same playing field (centered around 0 with a variance of 1), the AI's math will automatically assume the Accelerometer is "more important" just because the raw numbers are bigger.

  ### 4. Windowing (Time-Series Slicing)

  • What we are doing: Instead of feeding the AI one single row of data, we slice the continuous timeline into overlapping blocks (windows). For example, we grab 1.0 second of continuous IMU data (a block of 100 rows x 6 sensors) and pair it with the vehicle's speed at the very end of that 1-second block.
  • Why we are doing it: Velocity cannot be determined from a single frozen snapshot of an accelerometer. The Temporal Convolutional Network (TCN) needs to look at a sequence (history) of acceleration over time to deduce how fast the car is currently moving. This step restructures the flat CSV data into the 3D Tensors (Batch, Sequence, Features) that the neural network accepts as input.
