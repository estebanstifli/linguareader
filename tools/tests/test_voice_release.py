import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load('make_release').prepare
voice_files = load('publish_packs').default_voice_files


class VoiceReleaseTest(unittest.TestCase):
    def test_prepare_needs_no_alignment_model_and_does_not_recreate_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            packs = root / '.models/voice-pack'
            packs.mkdir(parents=True)
            (packs / 'voices-es-v1.zip').write_bytes(b'test voice package')
            languages = prepare(root)
            self.assertEqual({'es'}, set(languages))
            self.assertFalse((root / 'app/src/main/assets/aligners/catalog.json').exists())
            self.assertTrue((root / 'app/src/main/assets/packs/voices.json').exists())

    def test_default_publication_ignores_stale_weights_and_old_voice_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            release = Path(directory)
            for name in ['voices-es-v1.zip', 'voices-es-v0.zip', 'vp-es.model.onnx', 'w2v2-en.vocab.json', 'other.zip']:
                (release / name).write_bytes(b'preserved')
            (release / 'voices.json').write_text(json.dumps({'version':2,'languages':{'es':{'asset':'voices-es-v1.zip'}}}))
            self.assertEqual(['voices-es-v1.zip','voices.json'], [p.name for p in voice_files(release)])
            self.assertEqual(b'preserved',(release / 'vp-es.model.onnx').read_bytes())

    def test_voice_manifest_cannot_point_to_alignment_weights_or_outside_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            release = Path(directory)
            for name in ['vp-es.model.onnx', '../voices-es-v1.zip', '..\\voices-es-v1.zip']:
                (release / 'voices.json').write_text(json.dumps({'version':2,'languages':{'es':{'asset':name}}}))
                with self.assertRaises(ValueError): voice_files(release)


if __name__ == '__main__': unittest.main()
