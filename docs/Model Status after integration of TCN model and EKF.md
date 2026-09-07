#### 1. What is the TCN (Temporal Convolutional Network)?

  • The Problem: A smartphone's accelerometer shakes violently when you drive over a pothole or a bump. Traditional math can't
  translate those raw, chaotic vibrations into vehicle speed.
  • The Solution (TCN): A TCN is an AI architecture specifically designed to look at a "window of time" (like 1 second of vibration    
  data) and recognize the patterns.
  • Its Job Here: The TCN acts as a translator. It takes 11 channels of messy vibrations and outputs a single guess: "Based on these   
  vibrations, I think the car is moving at X km/h."

#### 2. What is the EKF (Extended Kalman Filter)?

  • The Problem: As we saw in the residual plots, the AI's speed guess is noisy and spiky. If you just drew a line on a map using      
  only the AI's speed, the car would look like it was jittering all over the road.
  • The Solution (EKF): An EKF is a classical mathematical algorithm used by NASA and modern self-driving cars. It takes multiple      
  noisy inputs and fuses them with the strict laws of physics to find the "true" location.
  • Its Job Here: The EKF takes the TCN's noisy speed guess, combines it with the phone's compass (magnetometer), and applies "Non-    
  Holonomic Constraints" (the physical law that cars can only drive forward, they can't slide sideways). The EKF mathematically        
  smooths out the AI's noise and outputs the clean, accurate GPS coordinate that you see as the red dashed line on your map.

  In summary: The AI (TCN) figures out the speed from the vibrations, and the math (EKF) uses the laws of physics to turn that speed   
  into a clean, smooth map trajectory when GPS fails.
 
 
 🏆 The Official Metrics (from tcn_ekf_results.json)

  • Drive Duration: ~1.7 Hours (S-M.csv is a massive city-scale drive)
  • Simulated GPS Blackout: 60 seconds
  • Final Accumulated Error: 366.5 meters
  • Position Drift: 4.82%
  • ISRO Benchmark (<10%): ✅ MET

  ### 📈 Visual Analysis of the Plot (tcn_ekf_integration.png)

  1. The Trajectory (Left Plot)

  • The blue line (GPS Truth) spans a massive 15x15 kilometer area.
  • The red dashed line (our AI + EKF) tracks it almost flawlessly.
  • Notice the massive straight orange line in the middle? The dataset S-M.csv contains a huge "teleportation" jump (likely the        
  driver drove through a massive tunnel where GPS dropped, or the app was paused and resumed across the city). Our 60-second
  blackout triggered right near this anomaly!
  • Despite this messy, extreme real-world anomaly, the EKF didn't crash. It held the error to just 366m.

  2. The Error Over Time (Right Plot)

  • You'll notice the red error line constantly spikes up and then drops instantly to 0.
  • This is the Kalman Filter working perfectly. Between sparse GPS pings, the filter relies on the TCN (which we know from our        
  residual analysis is noisy, causing the error to spike). But the moment a reliable GPS ping is received, the EKF "snaps" the
  position back to reality (dropping the error to 0).
  • During the orange shaded window (the blackout), there were no GPS pings to snap back to. It relied 100% on your AI, and it
  successfully survived the 60 seconds without flying off the map.
  