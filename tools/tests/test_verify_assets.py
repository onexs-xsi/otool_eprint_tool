import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from tools.verify_assets import verify


class VerifyAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = self.root / "manifest.json"
        self.payload = b"fixture-model"
        (self.root / "model.bin").write_bytes(self.payload)
        self.entry = {"path": "model.bin", "bytes": len(self.payload),
                      "sha256": hashlib.sha256(self.payload).hexdigest()}

    def write_manifest(self, **extra):
        self.manifest.write_text(json.dumps({"files": [self.entry], **extra}), encoding="utf-8")

    def test_intact_file_is_accepted(self):
        self.write_manifest()
        self.assertEqual(0, verify(self.manifest, self.root))

    def test_same_size_tampering_is_rejected(self):
        self.write_manifest()
        (self.root / "model.bin").write_bytes(b"fixture-other")
        self.assertEqual(1, verify(self.manifest, self.root))

    def test_missing_deployment_configuration_is_rejected(self):
        self.write_manifest(deploymentConfig={"path": "config.json", "bytes": 2,
                                             "sha256": hashlib.sha256(b"{}").hexdigest()})
        self.assertEqual(1, verify(self.manifest, self.root))

    def test_changed_deployment_configuration_is_rejected(self):
        self.write_manifest(deploymentConfig={"path": "config.json", "bytes": 2,
                                             "sha256": hashlib.sha256(b"{}").hexdigest()})
        (self.root / "config.json").write_bytes(b"[]")
        self.assertEqual(1, verify(self.manifest, self.root))

    def test_parent_directory_paths_are_rejected(self):
        self.entry["path"] = "../outside.bin"
        self.write_manifest()
        self.assertEqual(1, verify(self.manifest, self.root))


if __name__ == "__main__":
    unittest.main()
