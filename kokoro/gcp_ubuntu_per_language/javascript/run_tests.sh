#!/bin/bash

set -euo pipefail
cd ${KOKORO_ARTIFACTS_DIR}/git/tink

./kokoro/copy_credentials.sh

cd javascript
use_bazel.sh $(cat .bazelversion)
bazel build ...
bazel test --test_output=errors -- ...
