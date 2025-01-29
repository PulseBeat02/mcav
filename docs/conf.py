import os
import sys
from datetime import datetime

sys.path.insert(0, os.path.abspath(".."))

project = "MCAV Documentation"
author = "PulseBeat_02"
copyright = f"{datetime.now().year}, {author}"

html_theme_options = {
    "navigation_depth": 4,
    "collapse_navigation": False,
    "sticky_navigation": True
}

extensions = [
    "sphinx.ext.autodoc",
    "sphinx.ext.napoleon",
    "sphinx.ext.viewcode",
    "myst_parser",
]
templates_path = ["_templates"]
exclude_patterns = []

html_theme = "alabaster"
html_static_path = ["_static"]

jupyter_execute_notebooks = "off"