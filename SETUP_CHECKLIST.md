# Setup Checklist - Physical Prerequisites

## ⚠️ CRITICAL: You Must Complete These Steps

Your code is ready, but **it will fail to build** until you physically place these files. The build system cannot download them for you.

---

## 📋 Step-by-Step Checklist

### ✅ Step 1: Verify Directory Structure (1 min)

Run this command to create the required directories:

```bash
# From project root (c:\Users\Allan\private-ai)
mkdir -p shared/src/nativeInterop/cinterop/headers
mkdir -p shared/src/nativeInterop/cinterop/libs/macos-arm64
mkdir -p shared/src/nativeInterop/cinterop/libs/macos-x64
mkdir -p shared/src/nativeInterop/cinterop/libs/ios-arm64
mkdir -p shared/src/nativeInterop/cinterop/libs/windows-x64
```

Expected result:
```
shared/src/nativeInterop/cinterop/
├── headers/              # ← Will contain llama.h
└── libs/
    ├── macos-arm64/      # ← Will contain libllama.a for Apple Silicon
    ├── macos-x64/        # ← Will contain libllama.a for Intel Mac
    ├── ios-arm64/        # ← Will contain libllama.a for iPhone
    └── windows-x64/      # ← Will contain libllama.a for Windows
```

---

### ✅ Step 2: Get llama.h Header (2 min)

**Option A: Download from GitHub**
```bash
# Download the header file
curl -o shared/src/nativeInterop/cinterop/headers/llama.h \
  https://raw.githubusercontent.com/ggerganov/llama.cpp/master/llama.h
```

**Option B: Copy from Local Clone**
```bash
# If you already cloned llama.cpp
cp /path/to/llama.cpp/llama.h shared/src/nativeInterop/cinterop/headers/
```

**Verify**:
```bash
# Check file exists
ls -lh shared/src/nativeInterop/cinterop/headers/llama.h

# Should show: llama.h (around 50-100 KB)
```

---

### ✅ Step 3: Build llama.cpp for Your Platform (15-30 min)

**For macOS ARM64 (Apple Silicon)**

```bash
# Clone llama.cpp (if not already done)
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp

# Build for macOS ARM64 with Metal support
mkdir build-macos-arm64 && cd build-macos-arm64
cmake .. \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DLLAMA_METAL=ON \
  -DBUILD_SHARED_LIBS=OFF
cmake --build . --config Release

# Find the compiled library
# It will be in: build-macos-arm64/libllama.a
ls -lh libllama.a

# Copy to your project
cp libllama.a ../../private-ai/shared/src/nativeInterop/cinterop/libs/macos-arm64/
```

**For macOS x64 (Intel Mac)**

```bash
cd llama.cpp
mkdir build-macos-x64 && cd build-macos-x64
cmake .. \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES=x86_64 \
  -DLLAMA_ACCELERATE=ON \
  -DBUILD_SHARED_LIBS=OFF
cmake --build . --config Release

cp libllama.a ../../private-ai/shared/src/nativeInterop/cinterop/libs/macos-x64/
```

**For iOS ARM64**

```bash
cd llama.cpp
mkdir build-ios-arm64 && cd build-ios-arm64
cmake .. \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DLLAMA_METAL=ON \
  -DBUILD_SHARED_LIBS=OFF
cmake --build . --config Release

cp libllama.a ../../private-ai/shared/src/nativeInterop/cinterop/libs/ios-arm64/
```

**For Windows x64**

```bash
# On Windows with MinGW or MSVC
cd llama.cpp
mkdir build-windows-x64 && cd build-windows-x64
cmake .. -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF
cmake --build . --config Release

# Copy libllama.a to project
cp libllama.a ../../private-ai/shared/src/nativeInterop/cinterop/libs/windows-x64/
```

---

### ✅ Step 4: Verify Files Are in Place (1 min)

**Critical Check**:
```bash
# From project root
tree shared/src/nativeInterop/cinterop/

# Should show:
# cinterop/
# ├── headers/
# │   └── llama.h              ✓
# └── libs/
#     ├── macos-arm64/
#     │   └── libllama.a       ✓
#     ├── macos-x64/
#     │   └── libllama.a       ✓ (optional)
#     ├── ios-arm64/
#     │   └── libllama.a       ✓ (optional)
#     └── windows-x64/
#         └── libllama.a       ✓ (if on Windows)
```

**File Size Check**:
```bash
# libllama.a should be 100-500 MB depending on build options
ls -lh shared/src/nativeInterop/cinterop/libs/*/libllama.a

# Example output:
# -rw-r--r--  1 user  staff   287M Jan  7 10:30 macos-arm64/libllama.a
```

---

### ✅ Step 5: Test cinterop Generation (2 min)

**Try to generate Kotlin bindings**:

```bash
# For macOS ARM64
./gradlew :shared:cinteropLlamacppMacosArm64

# Expected output:
# BUILD SUCCESSFUL
# cinterop task completed
```

**If it fails**:
- ❌ "Cannot find llama.h" → Check Step 2
- ❌ "Cannot find -lllama" → Check Step 3
- ❌ "Undefined symbols" → Rebuild libllama.a with correct flags

**If it succeeds**:
```bash
# Check generated bindings
ls -la shared/build/generated/source/cinterop/llamacppMacosArm64/

# Should contain Kotlin files with llama.cpp bindings
```

---

## 🎯 Quick Verification Commands

Run these to verify everything is ready:

```bash
# 1. Header exists?
test -f shared/src/nativeInterop/cinterop/headers/llama.h && echo "✅ Header OK" || echo "❌ Missing header"

# 2. Library exists? (for your platform)
test -f shared/src/nativeInterop/cinterop/libs/macos-arm64/libllama.a && echo "✅ Library OK" || echo "❌ Missing library"

# 3. cinterop works?
./gradlew :shared:cinteropLlamacppMacosArm64 && echo "✅ cinterop OK" || echo "❌ cinterop failed"

# 4. Can build project?
./gradlew :core:inference-engine:build && echo "✅ Build OK" || echo "❌ Build failed"
```

---

## 🚀 Once Complete: Next Steps

### Test Native Implementation

```bash
# Build native binary
./gradlew :core:inference-engine:macosArm64Binaries

# Run tests
./gradlew :core:inference-engine:macosArm64Test
```

### Test Desktop Implementation (No native libs needed!)

```bash
# Desktop uses java-llama.cpp (pre-built JNI)
./gradlew :core:inference-engine:desktopTest

# Should work immediately with a GGUF model
```

---

## 🐛 Common Issues

### "Cannot find llama.h"

```bash
# Check the file is in the right place
ls shared/src/nativeInterop/cinterop/headers/llama.h

# If missing, download it:
curl -o shared/src/nativeInterop/cinterop/headers/llama.h \
  https://raw.githubusercontent.com/ggerganov/llama.cpp/master/llama.h
```

### "Undefined symbol: _llama_backend_init"

```bash
# Library was not built correctly. Rebuild with:
cd llama.cpp/build-macos-arm64
cmake .. -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF
cmake --build . --config Release --clean-first
```

### "Wrong architecture"

```bash
# Check architecture of libllama.a
file shared/src/nativeInterop/cinterop/libs/macos-arm64/libllama.a

# Should show: Mach-O 64-bit arm64 object

# If wrong, rebuild for correct architecture (see Step 3)
```

### "Desktop build fails with de.kherud.llama not found"

```bash
# Refresh dependencies
./gradlew --refresh-dependencies

# Clean and rebuild
./gradlew clean
./gradlew :core:inference-engine:desktopMainClasses
```

---

## 📊 Platform Support Matrix

| Platform | Header Needed? | Library Needed? | Status |
|----------|---------------|-----------------|---------|
| **Desktop (JVM)** | ❌ No | ❌ No (uses java-llama.cpp) | ✅ Works immediately |
| **macOS Native** | ✅ Yes | ✅ Yes | ⚠️ Requires build |
| **iOS Native** | ✅ Yes | ✅ Yes | ⚠️ Requires build |
| **Windows Native** | ✅ Yes | ✅ Yes | ⚠️ Requires build |
| **Android** | ✅ Yes | ✅ Yes | 🔮 Future |

**Recommendation**: Start with **Desktop (JVM)** for immediate testing, then add Native builds for production.

---

## ✅ Completion Criteria

You're ready when ALL of these pass:

```bash
# 1. Files exist
[ ] shared/src/nativeInterop/cinterop/headers/llama.h
[ ] shared/src/nativeInterop/cinterop/libs/<your-platform>/libllama.a

# 2. cinterop generates stubs
[ ] ./gradlew :shared:cinteropLlamacpp<Platform> succeeds

# 3. Project builds
[ ] ./gradlew build succeeds

# 4. Can load a model
[ ] Download a GGUF model to models/
[ ] Run test that loads model
[ ] See: "✅ Model loaded" in output
```

---

## 🎯 TL;DR - Minimum to Get Started

**If you just want to test on Desktop (fastest path)**:

```bash
# 1. Skip native builds entirely!
# 2. Just download a model
mkdir models
# Download TinyLlama from HuggingFace

# 3. Test Desktop implementation
./gradlew :core:inference-engine:desktopTest

# Desktop uses java-llama.cpp, so NO manual setup needed!
```

**For native iOS/macOS support**:

```bash
# 1. Get header
curl -o shared/src/nativeInterop/cinterop/headers/llama.h \
  https://raw.githubusercontent.com/ggerganov/llama.cpp/master/llama.h

# 2. Build llama.cpp
# (See Step 3 above)

# 3. Copy libllama.a
# (See Step 3 above)

# 4. Generate bindings
./gradlew :shared:cinteropLlamacppMacosArm64
```

---

**Status Check**: Run this command to see what's missing:

```bash
echo "Header: $(test -f shared/src/nativeInterop/cinterop/headers/llama.h && echo '✅' || echo '❌')"
echo "Library (macOS ARM64): $(test -f shared/src/nativeInterop/cinterop/libs/macos-arm64/libllama.a && echo '✅' || echo '❌')"
```

---

**Next**: Once you see ✅✅, proceed to [QUICK_START.md](QUICK_START.md) to test inference!
