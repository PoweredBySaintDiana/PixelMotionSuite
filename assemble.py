import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description='Build unsigned Pixel Motion Suite 2.3.14; sign separately with the release key.')
parser.add_argument('tools', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
tools = args.tools.resolve()
project = Path(__file__).resolve().parent / 'pixel-suite'
java = next((tools / 'jre').rglob('java.exe'))
with tempfile.TemporaryDirectory() as directory:
    tmp = Path(directory)
    classes, dex, assets = (tmp / name for name in ('classes', 'dex', 'assets'))
    classes.mkdir()
    dex.mkdir()
    shutil.copytree(project / 'assets', assets, ignore=lambda path, names: {'assets'} if Path(path) == project / 'assets' else set())
    sources = [path for name in ('src', 'compile-only') for path in (project / name).rglob('*.java')]
    subprocess.run([java, '-jar', tools / 'ecj.jar', '-8', '-proc:none', '-nowarn', '-cp', tools / 'platform-35/android.jar', '-d', classes, *sources], check=True)
    program = tmp / 'program.jar'
    with zipfile.ZipFile(program, 'w') as jar:
        for file in sorted((classes / 'dev').rglob('*.class')):
            jar.write(file, file.relative_to(classes))
    subprocess.run([java, '-cp', tools / 'r8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '28', '--lib', tools / 'platform-35/android.jar', '--classpath', classes, '--output', dex, program], check=True)
    resources = tmp / 'resources.apk'
    compiled = tmp / 'res.zip'
    aapt = tools / 'aapt2.exe'
    subprocess.run([aapt, 'compile', '--dir', project / 'res', '-o', compiled], check=True)
    subprocess.run([aapt, 'link', '-I', tools / 'framework.jar', '--manifest', project / 'AndroidManifest.xml', '--min-sdk-version', '37', '--target-sdk-version', '37', '-A', assets, '-o', resources, compiled], check=True)
    with zipfile.ZipFile(resources) as packaged, zipfile.ZipFile(args.output, 'w') as apk:
        for entry in packaged.infolist():
            apk.writestr(entry, packaged.read(entry))
        apk.write(dex / 'classes.dex', 'classes.dex', compress_type=zipfile.ZIP_STORED)
