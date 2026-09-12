# source ./env.sh   — sets up the Android toolchain for this project in the current shell.
# Uses the project-local Temurin JDK in tools/ if present, otherwise Homebrew's openjdk@17.
_ROOT="$(cd "$(dirname "${BASH_SOURCE:-$0}")" && pwd)"
if [ -d "$_ROOT/tools/jdk17/Contents/Home" ]; then
  export JAVA_HOME="$_ROOT/tools/jdk17/Contents/Home"
else
  export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
fi
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
