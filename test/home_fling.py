from pathlib import Path
import subprocess
import sys
import tempfile

here = Path(__file__).resolve().parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
source = here.parent / 'pixel-suite/src/dev/pixelmotion/a11/motion/HomeFling.java'
with tempfile.TemporaryDirectory() as output:
    subprocess.run([java, '-jar', tools/'ecj.jar', '-8', '-proc:none', '-nowarn', '-d', output,
                    source, here/'HomeFlingTest.java'], check=True)
    subprocess.run([java, '-cp', output, 'dev.pixelmotion.a11.motion.HomeFlingTest'], check=True)
