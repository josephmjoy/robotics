"""Temporarly fixes up the Python path and imports the module under test so
that unit tests work. As suggested in https://docs.python-guide.org/writing/structure/"""
import os
import sys

# pylint: disable=unused-import,wrong-import-position
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from robotutils import msgmap, concurrent_helper, config_helper, strmap_helper
from robotutils import logging_helper, comm_helper
import robotutils
