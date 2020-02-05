#
# Copyright 2020 DreamWorks Animation L.L.C.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from setuptools import setup, find_packages
from glob import glob


PACKAGE = "forestflow"

setup(
    name=PACKAGE,
    version="0.2.3",
    description="ForestFlow is a policy-driven Machine Learning Model Server.",
    author="DreamWorks Animation",
    author_email="forestflow@dreamworks.com",
    maintainer="Ahmad Alkilani, DreamWorks Animation",
    maintainer_email="ahmad.alkilani@dreamworks.com",
    url="https://github.com/dreamworksanimation/forestflow",
    long_description=open("README.md").read(),
    classifiers=[
        # Get classifiers from:
        # https://pypi.python.org/pypi?%3Aaction=list_classifiers
        "Development Status :: 5 - Production/Stable",
        "Natural Language :: English",
        "Operating System :: POSIX",
        "Programming Language :: Scala",
        "Programming Language :: Scala :: 2.12",
        "License :: OSI Approved :: Apache Software License",
    ],
    install_requires=[
        "recommonmark>=0.6.0,<1",
        "sphinx-rtd-theme<1",
        "Sphinx>=1.8.5"
    ]
)