#!/usr/bin/env python3
"""Regression for real Compose control names and genuinely unnamed controls."""
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('ui_semantics', pathlib.Path(__file__).with_name('android-ui-semantics.py'))
semantics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(semantics)


class ControlNamesTest(unittest.TestCase):
    def test_actual_compose_capture_names_are_on_synthetic_children(self):
        xml = pathlib.Path(__file__).with_name('fixtures').joinpath('android-settings-controls.xml').read_text()
        self.assertEqual(semantics.named_toggles(xml), {'Sound effects', 'Reduce motion', 'Keyboard shortcuts'})

    def test_page_text_cannot_name_an_unnamed_toggle(self):
        xml = '<hierarchy><node text="Sound effects"/><node checkable="true"><node/></node></hierarchy>'
        self.assertEqual(semantics.named_toggles(xml), set())


if __name__ == '__main__':
    unittest.main()
