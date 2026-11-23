"""Exercise the actual Bash readiness function with deterministic, network-free HTTP results."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class RestartReadiness(unittest.TestCase):
    def probe(self, statuses):
        with tempfile.TemporaryDirectory() as folder:
            script = r'''
source scripts/vault-restart-readiness.sh
curl_args=()
scratch="$PROBE_DIR"
base=https://fixture.invalid
printf '0' > "$scratch/count"
curl() {
  local count code
  count=$(cat "$scratch/count")
  count=$((count+1))
  printf '%s' "$count" > "$scratch/count"
  code=$(sed -n "${count}p" "$scratch/codes")
  printf '%s' "$code"
  [[ "$code" != 000 ]]
}
sleep() { :; }
await_vault_restart_fixture
result=$?
printf 'calls=%s\n' "$(cat "$scratch/count")"
exit "$result"
'''
            Path(folder, "codes").write_text("\n".join(statuses) + "\n")
            result = subprocess.run(["bash", "-c", script], env={**os.environ, "PROBE_DIR": folder},
                                    capture_output=True, text=True, check=False)
            return result

    def test_transient_storage_failure(self):
        result = self.probe(["500", "200"])
        self.assertEqual(result.returncode, 0)
        self.assertIn("calls=2", result.stdout)

    def test_transport_and_unavailable(self):
        result = self.probe(["000", "503", "200"])
        self.assertEqual(result.returncode, 0)
        self.assertIn("calls=3", result.stdout)

    def test_denied_or_missing_is_not_retried(self):
        for status in ["401", "403", "404", "400"]:
            with self.subTest(status=status):
                result = self.probe([status, "200"])
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("calls=1", result.stdout)

    def test_persistent_failure_is_bounded(self):
        result = self.probe(["500"] * 16)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("calls=15", result.stdout)

    def test_success_needs_no_retry(self):
        result = self.probe(["200"])
        self.assertEqual(result.returncode, 0)
        self.assertIn("calls=1", result.stdout)


if __name__ == "__main__":
    unittest.main()
