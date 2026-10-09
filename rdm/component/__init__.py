"""RDM as one WASI component (DI-87): the composition root of ``rdm.wasm``.

``app.py`` is the entry the host runs (``wasi:cli/run``). It chooses the two
providers the component was composed with, through the ports the contexts
define: the record-state port (``rdm.kernel.record_state``) answered by the
``rdm:component/record-state`` import, and the c4 port
(``rdm.architecture.draw``) answered by the ``rdm:component/c4`` import. The
rest of RDM is the same code the command line runs. Built by
``scripts/build-component.sh``; this package is never imported natively.
"""
