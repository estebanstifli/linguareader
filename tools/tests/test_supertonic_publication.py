import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock


spec = importlib.util.spec_from_file_location("publish_supertonic", Path(__file__).resolve().parents[1] / "publish_supertonic.py")
publication = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = publication
spec.loader.exec_module(publication)


class SupertonicPublicationTest(unittest.TestCase):
    def test_production_allowlist_includes_license_but_not_derived_or_alignment_models(self):
        catalog = publication.read_catalog()
        self.assertEqual(17, len(catalog.files))
        self.assertEqual(401_291_751, catalog.total_bytes)
        self.assertEqual("LICENSE", catalog.files[0].path)
        self.assertFalse(any("timing" in item.path or "align" in item.path for item in catalog.files))
        self.assertEqual("models/supertonic-3/" + catalog.revision, catalog.prefix)
        documents = publication.publication_documents(catalog)
        self.assertEqual("manifest.json", list(documents)[-1])
        manifest = json.loads(documents["manifest.json"])
        self.assertFalse(manifest["weightsModified"])
        self.assertEqual("LICENSE", manifest["license"]["path"])
        self.assertEqual(17, len(documents["SHA256SUMS"].splitlines()))

    def test_unsafe_paths_are_rejected(self):
        for value in ["../weights", "x/../../weights", "C:/weights", "/weights", "x\\weights", "x//weights", "x/./weights"]:
            self.assertFalse(publication.safe_model_path(value), value)

    def test_all_local_sources_are_validated_before_publication(self):
        with tempfile.TemporaryDirectory() as root:
            file = self.make_file(root)
            catalog = publication.Catalog("a" * 40, "owner/repository", "license", (
                publication.ModelFile(file.path, file.size, file.sha256),
            ))
            self.assertEqual([file], publication.validate_sources(catalog, Path(root)))
            file.source.write_bytes(b"changed original")
            with self.assertRaisesRegex(ValueError, "integrity mismatch"):
                publication.validate_sources(catalog, Path(root))

    def make_file(self, root):
        path = Path(root) / "LICENSE"
        path.write_bytes(b"original pinned license")
        return publication.PublicationFile("LICENSE", path, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_resume_checks_remote_bytes_and_never_reuploads_matching_object(self):
        with tempfile.TemporaryDirectory() as root:
            file = self.make_file(root)
            put = Mock()
            identify = Mock(return_value=(file.size, file.sha256))
            self.assertEqual("verified-existing", publication.publish_one(file, "models/supertonic-3/revision", True, identify, put))
            put.assert_not_called()

    def test_existing_different_object_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            file = self.make_file(root)
            put = Mock()
            with self.assertRaisesRegex(RuntimeError, "Refusing to overwrite"):
                publication.publish_one(file, "models/supertonic-3/revision", True, Mock(return_value=(file.size, "0" * 64)), put)
            put.assert_not_called()

    def test_missing_object_is_published_then_verified_and_verify_only_cannot_upload(self):
        with tempfile.TemporaryDirectory() as root:
            file = self.make_file(root)
            put = Mock()
            with self.assertRaisesRegex(RuntimeError, "Missing public object"):
                publication.publish_one(file, "models/supertonic-3/revision", False, Mock(return_value=None), put)
            put.assert_not_called()
            identify = Mock(side_effect=[None, (file.size, file.sha256)])
            self.assertEqual("uploaded-and-verified", publication.publish_one(file, "models/supertonic-3/revision", True, identify, put))
            put.assert_called_once()
            self.assertEqual(2, identify.call_count)

    def test_changed_source_and_corrupt_public_upload_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            file = self.make_file(root)
            put = Mock()
            with self.assertRaisesRegex(RuntimeError, "Public verification failed"):
                publication.publish_one(file, "models/supertonic-3/revision", True,
                    Mock(side_effect=[None, (file.size, "0" * 64)]), put)
            file.source.write_bytes(b"changed source")
            put.reset_mock()
            with self.assertRaisesRegex(RuntimeError, "Source changed"):
                publication.publish_one(file, "models/supertonic-3/revision", True, Mock(return_value=None), put)
            put.assert_not_called()


if __name__ == "__main__":
    unittest.main()
