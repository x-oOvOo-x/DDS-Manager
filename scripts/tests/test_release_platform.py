"""Offline regression tests for the official release uploader (no live tokens)."""
import argparse
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("release_publisher", ROOT / "scripts/publish-platform.py")
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class ReleaseUploadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        folder = Path(self.tmp.name)
        self.version = "0.7.0"
        self.jar = folder / "DDS-Manager-0.7.0.jar"
        self.jar.write_bytes(b"jar-content")
        self.digest = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.sha = folder / "DDS-Manager-0.7.0.jar.sha256"
        self.sha.write_text(f"{self.digest}  {self.jar.name}\n", "ascii")
        self.notes = folder / "notes.md"
        self.notes.write_text("Release notes", "utf-8")
        self.config = folder / "config.json"
        self.config.write_text(json.dumps({
            "velocity_versions": ["3.4.0"],
            "minecraft_versions": ["1.21.11"],
            "curseforge_version_ids": [],
            "minecraft_support_policy": {"minimum": "1.14.4", "current_ceiling": "26.3"},
        }), "utf-8")
        self.args = argparse.Namespace(version=self.version, jar=self.jar, sha256=self.sha,
                                       changelog=self.notes, config=self.config)

    def test_verified_release(self):
        data, jar, notes, digest = publisher.load_release(self.args)
        self.assertEqual(digest, self.digest)
        self.assertEqual(jar, self.jar)
        self.assertEqual(notes, "Release notes")
        self.assertEqual(data["velocity_versions"], ["3.4.0"])

    def test_rejects_changed_jar(self):
        self.jar.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "SHA256"):
            publisher.load_release(self.args)

    def test_each_platform_validates_only_its_own_versions(self):
        config, _, _, _ = publisher.load_release(self.args)
        publisher.validate_target("hangar", config)
        publisher.validate_target("modrinth", config)
        with self.assertRaisesRegex(ValueError, "curseforge_version_ids"):
            publisher.validate_target("curseforge", config)
        config["minecraft_versions"] = []
        publisher.validate_target("hangar", config)
        with self.assertRaisesRegex(ValueError, "minecraft_versions"):
            publisher.validate_target("modrinth", config)

    def test_curseforge_version_ids_cannot_be_guessed(self):
        with self.assertRaisesRegex(ValueError, "positive numeric"):
            publisher.validate_target("curseforge", {"curseforge_version_ids": ["26.3"]})
        publisher.validate_target("curseforge", {"curseforge_version_ids": [42]})
        with self.assertRaisesRegex(ValueError, "duplicate"):
            publisher.validate_target("curseforge", {"curseforge_version_ids": [42, 42]})

    def test_hangar_multipart_uses_binary_field(self):
        body, content_type = publisher.multipart("versionUpload", {"version": "0.7.0"}, self.jar)
        body = body.replace(b'name="file";', b'name="files";', 1)
        self.assertIn(b'name="versionUpload"', body)
        self.assertIn(b'name="files"; filename=', body)
        self.assertIn(b"jar-content", body)
        self.assertIn("boundary=", content_type)

    def test_modrinth_matching_existing_sha_skips_upload(self):
        with patch.dict(os.environ, {"MODRINTH_TOKEN": "test", "MODRINTH_PROJECT_SLUG": "dds-manager"}):
            with patch.object(publisher, "api", return_value=(200, {
                "files": [{"filename": self.jar.name, "primary": True, "hashes": {"sha256": self.digest}}]
            })) as api:
                publisher.publish_modrinth(self.args, {}, self.jar, "notes", self.digest)
        self.assertEqual(api.call_count, 1)

    def test_modrinth_different_existing_sha_fails_closed(self):
        with patch.dict(os.environ, {"MODRINTH_TOKEN": "test", "MODRINTH_PROJECT_SLUG": "dds-manager"}):
            with patch.object(publisher, "api", return_value=(200, {
                "files": [{"hashes": {"sha256": "0" * 64}}]
            })):
                with self.assertRaisesRegex(RuntimeError, "different JAR"):
                    publisher.publish_modrinth(self.args, {}, self.jar, "notes", self.digest)

    def test_modrinth_upload_uses_canonical_project_id(self):
        responses = [(404, {}), (200, {"id": "ABCD1234"}),
                     (200, [{"version": "1.21.11", "version_type": "release"}]),
                     (200, {"id": "VERSIONID"})]
        with patch.dict(os.environ, {"MODRINTH_TOKEN": "test", "MODRINTH_PROJECT_SLUG": "dds-manager"}):
            with patch.object(publisher, "api", side_effect=responses) as http:
                publisher.publish_modrinth(self.args, {"minecraft_versions": ["1.21.11"]},
                                           self.jar, "notes", self.digest)
        self.assertEqual(http.call_count, 4)
        self.assertIn(b'"project_id": "ABCD1234"', http.call_args.kwargs["data"])

    def test_hangar_existing_version_requires_hash_review(self):
        with patch.dict(os.environ, {"HANGAR_API_TOKEN": "test", "HANGAR_PROJECT": "DDS-Manager"}):
            with patch.object(publisher, "api", return_value=(200, {"version": "0.7.0"})) as http:
                with self.assertRaisesRegex(RuntimeError, "unverified"):
                    publisher.publish_hangar(self.args, {"velocity_versions": ["3.4.0"]},
                                             self.jar, "notes", self.digest)
        self.assertEqual(http.call_count, 1)

    def test_curseforge_rejects_unknown_version_id_without_upload(self):
        with patch.dict(os.environ, {"CURSEFORGE_TOKEN": "test", "CURSEFORGE_PROJECT_ID": "1697972"}):
            with patch.object(publisher, "api", return_value=(200, [
                {"id": 12, "name": "26.3", "gameVersionTypeID": 42}
            ])) as http:
                with self.assertRaisesRegex(RuntimeError, "Unknown CurseForge"):
                    publisher.publish_curseforge(self.args, {"curseforge_version_ids": [999]},
                                                self.jar, "notes", self.digest)
        self.assertEqual(http.call_count, 1)

    def test_curseforge_duplicate_is_not_assumed_verified(self):
        responses = [
            (200, [{"id": 77, "name": "26.3"}]),
            (409, {"description": "duplicate file"}),
        ]
        with patch.dict(os.environ, {"CURSEFORGE_TOKEN": "test", "CURSEFORGE_PROJECT_ID": "1697972"}):
            with patch.object(publisher, "api", side_effect=responses):
                with self.assertRaisesRegex(RuntimeError, "remote SHA256"):
                    publisher.publish_curseforge(self.args, {"curseforge_version_ids": [77]},
                                                self.jar, "notes", self.digest)

    def test_modrinth_nonprimary_file_does_not_match(self):
        with patch.dict(os.environ, {"MODRINTH_TOKEN": "test", "MODRINTH_PROJECT_SLUG": "dds-manager"}):
            with patch.object(publisher, "api", return_value=(200, {
                "files": [{"filename": self.jar.name, "primary": False, "hashes": {"sha256": self.digest}}]
            })):
                with self.assertRaisesRegex(RuntimeError, "different JAR"):
                    publisher.publish_modrinth(self.args, {}, self.jar, "notes", self.digest)

    def test_declared_minecraft_range_is_a_plan_not_runtime_proof(self):
        config = json.loads((ROOT / "scripts/release-platforms.json").read_text("utf-8"))
        policy = config["minecraft_support_policy"]
        self.assertEqual(policy["minimum"], "1.14.4")
        self.assertEqual(policy["current_ceiling"], "26.3")
        self.assertEqual(policy["future_26x"], "review_each_new_release")
        self.assertEqual(policy["claim"], "intended_compatibility_range_not_full_runtime_verification")
        self.assertEqual(policy["per_version_runtime_evidence"], "not_recorded")
        self.assertEqual(config["minecraft_versions"], [])
        self.assertEqual(config["curseforge_version_ids"], [])
        with self.assertRaisesRegex(ValueError, "minecraft_versions"):
            publisher.validate_target("modrinth", config)
        with self.assertRaisesRegex(ValueError, "curseforge_version_ids"):
            publisher.validate_target("curseforge", config)

    def test_modrinth_versions_use_exact_releases_within_declared_bounds(self):
        policy = {"minimum": "1.14.4", "current_ceiling": "26.3"}
        for value in ("1.14.4", "1.21.11", "26.3"):
            publisher.validate_target("modrinth", {
                "minecraft_versions": [value], "minecraft_support_policy": policy})
        for value in ("1.14.3", "26.4", "1.21.x", "26.3-pre1"):
            with self.assertRaises(ValueError, msg=value):
                publisher.validate_target("modrinth", {
                    "minecraft_versions": [value], "minecraft_support_policy": policy})

    def test_modrinth_catalog_rejects_missing_or_snapshot_tags(self):
        responses = [(404, {}), (200, {"id": "ABCD1234"}),
                     (200, [{"version": "1.21.11", "version_type": "snapshot"}])]
        with patch.dict(os.environ, {"MODRINTH_TOKEN": "test", "MODRINTH_PROJECT_SLUG": "dds-manager"}):
            with patch.object(publisher, "api", side_effect=responses) as http:
                with self.assertRaisesRegex(RuntimeError, "Unknown/non-release"):
                    publisher.publish_modrinth(self.args, {"minecraft_versions": ["1.21.11"]},
                                               self.jar, "notes", self.digest)
        self.assertEqual(http.call_count, 3)

    def test_platform_fanout_is_independent(self):
        workflow = (ROOT / ".github/workflows/official-release.yml").read_text("utf-8")
        for platform in ("hangar", "modrinth", "curseforge"):
            self.assertIn(f"\n  {platform}:\n    needs: prepare\n", workflow)
        self.assertIn("needs: [prepare, hangar, modrinth, curseforge]", workflow)
        self.assertIn("if: always()", workflow)


if __name__ == "__main__":
    unittest.main()
