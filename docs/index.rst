.. ForestFlow documentation master file, created by
   sphinx-quickstart on Thu Oct 10 15:07:19 2019.
   You can adapt this file completely to your liking, but it should at least
   contain the root `toctree` directive.

ForestFlow
======================================

.. image:: ./../resources/forestflow_logo_text.png
   :target: ./index.rst
   :alt: ForestFlow

ForestFlow is a scalable policy-based cloud-native machine learning model server. ForestFlow strives to strike a
balance between the flexibility it offers data scientists and the adoption of standards while reducing
friction between Data Science, Engineering and Operations teams.

ForestFlow is policy-based because we believe automation for Machine Learning/Deep Learning operations is critical to
scaling human resources. ForestFlow lends itself well to workflows based on automatic retraining, version control,
A/B testing, Canary Model deployments, Shadow testing, automatic time or performance-based model deprecation and
time or performance-based model routing in real-time.

Our aim with ForestFlow is to provide data scientists a simple means to deploy models to a production system with
minimal friction accelerating the development to production value proposition.

To achieve these goals, ForestFlow looks to address the proliferation of model serving formats and standards for
inference API specifications by adopting, what we believe, are currently, or are becoming widely adopted open source
frameworks, formats, and API specifications. We do this in a pluggable format such that we can continue to evolve
ForestFlow as the industry and space matures and we see a need for additional support.

ForestFlow is developed and maintained by `DreamWorks Animation <http://www.dreamworksanimation.com>`_

Development Repository
^^^^^^^^^^^^^^^^^^^^^^

This GitHub repository hosts the trunk of the ForestFlow development. This implies that it is the newest public
version with the latest features and bug fixes. However, it also means that it has not undergone a lot of testing and
is generally less stable than the `production releases <https://github.com/dreamworksanimation/ForestFlow/releases>`_.

License
^^^^^^^

ForestFlow is released under the `Apache 2.0`_ license, which is a free, open-source, and detailed software license
developed and maintained by the Apache Software Foundation.

Contents
========

User Documentation

.. toctree::
   :maxdepth: 3

   Overview <overview>
   Quick Start Guide <quickstart>
   Building and Configuration <buildconfig>
   Inference <inference>
   Concepts <concepts>


.. _Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0
