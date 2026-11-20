from pathlib import Path
import subprocess
import sys
import tempfile

here = Path(__file__).resolve().parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
source = here.parent / 'pixel-suite/src/dev/pixelsuite/splash'
with tempfile.TemporaryDirectory() as temp:
    subprocess.run([java, '-jar', tools/'ecj.jar', '-8', '-proc:none', '-nowarn', '-d', temp,
                    source/'SplashPolicy.java', source/'LaunchLayers.java', here/'SplashTest.java'], check=True)
    subprocess.run([java, '-cp', temp, 'dev.pixelsuite.splash.SplashTest'], check=True)
