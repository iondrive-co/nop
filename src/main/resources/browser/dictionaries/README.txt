Chromium English spelling dictionaries

Source: https://chromium.googlesource.com/chromium/deps/hunspell_dictionaries/
Revision: cccf64a8acc951afe3f47fee023908e55699bc58

The filenames match the dictionary versions requested by the pinned CEF runtime
in native/cef/runtime.properties. When updating that runtime, check whether it
requests new dictionary versions and update these files and SlackSpelling together.
The Chromium spelling smoke test runs without network access to the dictionaries.

The .bdic files are unmodified upstream files. Retrieve a file at the revision
above through Gitiles with ?format=TEXT, then base64-decode the response.
README_en_AU.txt, README_en_GB.txt and README_en_US.txt contain the dictionaries'
SCOWL copyright and license notices. LICENSE is the upstream repository license.

SHA-256:
9e045c63b05e5435cd2045c8db9e2e15239a896d3363298fcbba2a3890d74eb1  en-AU-10-1.bdic
eaed4a709abdbd2f14aa143f25fe9c4c1b44591c7cb1942f75f968fb2fcd3cf8  en-GB-10-1.bdic
a075b01d9b015c616511a9e87da77da3d9881621db32f584e4606ddabf1c1100  en-US-10-1.bdic
