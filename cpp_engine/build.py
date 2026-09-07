import urllib.request
import zipfile
import os
import subprocess

def main():
    # 1. Download and Extract Eigen3
    if not os.path.exists('eigen-3.4.0'):
        print('Downloading Eigen3 (Header-only library)...')
        url = 'https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.zip'
        urllib.request.urlretrieve(url, 'eigen.zip')
        
        print('Extracting Eigen3 (takes a few seconds)...')
        with zipfile.ZipFile('eigen.zip', 'r') as z:
            z.extractall('.')
        os.remove('eigen.zip')

    # 2. Compile using MinGW (g++)
    print('Compiling C++ Engine with g++...')
    cmd = [
        'g++', '-std=c++17', 
        '-I./include', '-I./eigen-3.4.0', 
        'src/main.cpp', 'src/ekf.cpp', 
        '-o', 'idr_engine.exe'
    ]
    
    res = subprocess.run(cmd, capture_output=True, text=True)

    # 3. Run the Engine
    if res.returncode == 0:
        print('[SUCCESS] Compilation successful!\n')
        print('--- Running idr_engine.exe ---')
        subprocess.run('idr_engine.exe')
    else:
        print('[FAILED] Compilation failed:')
        print(res.stderr)

if __name__ == '__main__':
    main()
