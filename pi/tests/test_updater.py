from pathlib import Path

from eglo_bridge import updater


def test_parse_version_file():
    assert updater.parse_version_file('"""x"""\n\n__version__ = "0.6.1"\n') == "0.6.1"
    assert updater.parse_version_file("nothing here") is None


def test_is_newer():
    assert updater.is_newer("0.10.0", "0.9.9")
    assert updater.is_newer("0.6.1", "0.6.0")
    assert not updater.is_newer("0.6.0", "0.6.0")
    assert not updater.is_newer("0.5.9", "0.6.0")
    assert not updater.is_newer(None, "0.6.0")


def test_request_update(tmp_path: Path):
    updater.request_update(tmp_path)
    assert (tmp_path / "update-request").exists()
