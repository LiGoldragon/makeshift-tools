{
  description = "Makeshift tools and their self-contained test environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  inputs.ethos-zero.url = "github:LiGoldragon/ethos-zero/2999292dc67c8608e54d32c333c550b29153cbff";
  inputs.tree-sitter-ethos = {
    url = "github:LiGoldragon/tree-sitter-ethos";
    flake = false;
  };
  inputs.tree-sitter-datom = {
    url = "github:LiGoldragon/tree-sitter-dotos";
    flake = false;
  };

  outputs = { self, nixpkgs, ethos-zero, tree-sitter-ethos, tree-sitter-datom }:
    assert ethos-zero.rev == builtins.baseNameOf (builtins.fromJSON (builtins.readFile ./book-ethos.json)).validator;
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forSystems = nixpkgs.lib.genAttrs systems;
      testInputs = pkgs: with pkgs; [
        babashka
        chromium
        inotify-tools
        python3
        git
        bash
        coreutils
        nix
        tree-sitter
        stdenv.cc
      ];
      testEnvironment = pkgs: {
        BOOK_ETHOS = pkgs.writeText "book-ethos-test.json" (builtins.toJSON {
          validator = "${ethos-zero.packages.${pkgs.stdenv.hostPlatform.system}.default}/bin/ethos-zero";
        });
        BOOK_GRAMMARS = pkgs.writeText "book-grammars-test.json" (builtins.toJSON (
          let authored = builtins.fromJSON (builtins.readFile ./book-grammars.json);
          in authored // {
            grammars = authored.grammars // {
              rust = authored.grammars.rust // { source = "${pkgs.tree-sitter-grammars.tree-sitter-rust.src}"; };
              bash = authored.grammars.bash // { source = "${pkgs.tree-sitter-grammars.tree-sitter-bash.src}"; };
              ethos = authored.grammars.ethos // { source = "${tree-sitter-ethos}"; };
              datom = authored.grammars.datom // { source = "${tree-sitter-datom}"; };
            };
          }
        ));
        FONTCONFIG_FILE = pkgs.makeFontsConf { fontDirectories = [ pkgs.dejavu_fonts ]; };
      };
    in {
      devShells = forSystems (system:
        let pkgs = nixpkgs.legacyPackages.${system};
        in {
          default = pkgs.mkShell ((testEnvironment pkgs) // {
            packages = testInputs pkgs;
            shellHook = ''
              export XDG_CACHE_HOME="$(mktemp -d)"
            '';
          });
        });

      checks = forSystems (system:
        let pkgs = nixpkgs.legacyPackages.${system};
        in {
          tests = pkgs.stdenvNoCC.mkDerivation ((testEnvironment pkgs) // {
            pname = "makeshift-tools-tests";
            version = "0";
            src = self;
            nativeBuildInputs = testInputs pkgs;
            dontConfigure = true;
            buildPhase = ''
              runHook preBuild
              export HOME="$TMPDIR/home"
              mkdir -p "$HOME"
              bb test
              runHook postBuild
            '';
            installPhase = ''
              touch "$out"
            '';
          });
        });
    };
}
