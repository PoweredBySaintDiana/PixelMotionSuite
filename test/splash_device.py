import pathlib
import subprocess
import sys
import tempfile
import uuid
import zipfile

here = pathlib.Path(__file__).resolve().parent
tools = pathlib.Path(sys.argv[1]).resolve()
source = here.parent / 'pixel-suite/src/dev/pixelsuite/splash'
java = next((tools/'jre').rglob('java.exe'))
android = tools/'platform-35/android.jar'
adb = [tools/'platform-tools/adb.exe', '-s', sys.argv[2]]
remote = '/data/local/tmp/pms-splash-test-' + uuid.uuid4().hex + '.jar'
with tempfile.TemporaryDirectory() as temp:
    root = pathlib.Path(temp)
    classes = root/'classes'
    classes.mkdir()
    subprocess.run([java, '-jar', tools/'ecj.jar', '-8', '-proc:none', '-nowarn', '-cp', android, '-d', classes,
                    source/'SplashPolicy.java', source/'LayerImage.java', here/'SplashDeviceTest.java'], check=True)
    jar = root/'program.jar'
    with zipfile.ZipFile(jar, 'w') as built:
        for file in classes.rglob('*.class'):
            built.write(file, file.relative_to(classes))
    dex = root/'test.jar'
    subprocess.run([java, '-cp', tools/'r8.jar', 'com.android.tools.r8.D8', '--min-api', '28', '--lib', android, '--output', dex, jar], check=True)
    subprocess.run([*adb,'push',dex,remote], check=True)
    try:
        subprocess.run([*adb,'shell','chmod','0444',remote],check=True)
        subprocess.run([*adb,'shell','env','CLASSPATH='+remote,'app_process','/system/bin','dev.pixelsuite.splash.SplashDeviceTest'],check=True,timeout=20)
    finally:
        subprocess.run([*adb,'shell','rm','-f',remote],check=True,timeout=10)
