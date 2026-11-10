Feature: Adopt a repository
  An existing repository can be brought under RDM from a single command, without disturbing its current contents.

  @item-01-003
  Scenario: RDM shall bring an existing repository under RDM from one command: lay down the DHF skeleton (V&V plan, traceability matrix, render configuration, README), a CI gate workflow and a .gitignore of what RDM generates, skipping (never overwriting) any destination that already exists, a symbolic link included, and naming the .gitignore lines to add when it keeps an existing one.
