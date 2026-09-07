**Why PTQ Fails here:**
Dead reckoning relies on mathematical integration (adding up velocity over time to calculate distance). This means errors compound exponentially.

If you use PTQ, the process mathematically "chops off" decimals to force the 32-bit numbers into 8-bit integers. This introduces a tiny rounding error.
Imagine PTQ introduces a rounding error of just 0.2 meters per second in your AI's speed prediction.       
	  • After 10 seconds in a tunnel, your car is off by 2 meters.
	  • After 1 minute, you have drifted 12 meters.
	  • After a 3-minute tunnel, you have drifted 36 meters off the road—simply because of PTQ rounding errors! You will likely fail the ISRO benchmark (<10% drift).

**The QAT Solution:**
QAT solves this by simulating those exact 8-bit rounding errors while the AI is training.
Because the AI experiences the "chopped off decimals" during the training loop, the optimizer actually learns how to adjust its weights to compensate for that noise. When you finally export the model to the smartphone, it is highly robust and retains almost the exact same precision as the heavy 32-bit desktop model.
