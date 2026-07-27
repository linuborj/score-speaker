{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    # Java
    jdk17

    # Graphics libraries for Compose Desktop (Skiko)
    libGL
    xorg.libX11
    xorg.libXext
    xorg.libXrender
    xorg.libXtst
    xorg.libXi
    fontconfig
    freetype
  ];

  # Make sure native libraries can be found
  LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [
    pkgs.libGL
    pkgs.xorg.libX11
    pkgs.xorg.libXext
    pkgs.xorg.libXrender
    pkgs.xorg.libXtst
    pkgs.xorg.libXi
    pkgs.fontconfig
    pkgs.freetype
  ];

  shellHook = ''
    export JAVA_HOME="${pkgs.jdk17}"
    echo "ScoreSpeaker development shell"
    echo "Java: $(java -version 2>&1 | head -1)"
    echo ""
    echo "Run desktop app: ./gradlew :desktopApp:run"
  '';
}
