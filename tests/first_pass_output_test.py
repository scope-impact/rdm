
from rdm.publishing.first_pass_output import FirstPassOutput


class TestFirstPassOutput:
    def test_create(self):
        first_pass_output = FirstPassOutput()
        assert first_pass_output is not None
        assert not first_pass_output


