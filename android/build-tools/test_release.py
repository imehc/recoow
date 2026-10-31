import unittest

from release import check_order


class ReleaseOrderingTests(unittest.TestCase):
    def test_both_display_version_and_install_order_must_increase(self):
        previous = {"packageName": "imehc.recoow", "versionName": "0.6.9", "versionCode": 40}
        check_order(previous, {"versionName": "0.6.10", "versionCode": 41})
        for name, code in (("0.6.9", 41), ("0.6.8", 42), ("0.7.0", 40), ("0.7.0", 1)):
            with self.assertRaises(ValueError):
                check_order(previous, {"versionName": name, "versionCode": code})

    def test_bad_published_metadata_fails_closed(self):
        for previous in ({}, {"packageName": "other", "versionCode": 40},
                         {"packageName": "imehc.recoow", "versionCode": True}):
            with self.assertRaises(ValueError):
                check_order(previous, {"versionName": "0.7.0", "versionCode": 41})


if __name__ == "__main__":
    unittest.main()
