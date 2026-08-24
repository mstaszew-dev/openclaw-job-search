"""Smoke: both packages are importable under pythonpath=src."""


def test_packages_importable() -> None:
    import jobapps  # noqa: F401
    import jobhermes  # noqa: F401
