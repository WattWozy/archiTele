class Archtelemetry < Formula
  desc "Architectural governance layer for AI-assisted development"
  homepage "https://github.com/archtelemetry/archtelemetry"
  version "PLACEHOLDER"
  license "MIT"

  on_macos do
    on_arm do
      url "https://github.com/archtelemetry/archtelemetry/releases/download/v#{version}/archtelemetry-darwin-arm64"
      sha256 "PLACEHOLDER_SHA256_DARWIN_ARM64"
    end
    on_intel do
      url "https://github.com/archtelemetry/archtelemetry/releases/download/v#{version}/archtelemetry-darwin-amd64"
      sha256 "PLACEHOLDER_SHA256_DARWIN_AMD64"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/archtelemetry/archtelemetry/releases/download/v#{version}/archtelemetry-linux-amd64"
      sha256 "PLACEHOLDER_SHA256_LINUX_AMD64"
    end
    on_arm do
      url "https://github.com/archtelemetry/archtelemetry/releases/download/v#{version}/archtelemetry-linux-arm64"
      sha256 "PLACEHOLDER_SHA256_LINUX_ARM64"
    end
  end

  def install
    binary = Dir["archtelemetry-*"].first
    bin.install binary => "archtelemetry"
  end

  test do
    system "#{bin}/archtelemetry", "--version"
  end
end
