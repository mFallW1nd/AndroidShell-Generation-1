import os
import argparse

parser = argparse.ArgumentParser()

parser.add_argument(
    "--filename",
    "-f",
    help="The filename of apk to repack"
)

parser.add_argument(
    "--directory",
    "-d",
    help="The directory name of the unpacked apk"
)

parser.add_argument(
    "--build",
    "-b",
    help="The build type, release or debug"
)

args = parser.parse_args()

os.system("java -jar apktool.jar d " + args.filename + " -o " + args.directory)

os.system("java -jar mergeDexApk.jar " + args.build)
os.system("copy " + "/y classes.dex " + args.directory)

os.system("java -jar apktool.jar b " + args.directory + " -o " + args.filename)
os.system("jarsigner -storepass 111111 -verbose -keystore my.keystore " + args.filename + " my.keystore")

os.system("adb install -r -d" + args.filename)