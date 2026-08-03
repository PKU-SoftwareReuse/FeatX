import unittest

import numpy as np

from featx_pybackend.summary.clustering import compute_link
from featx_pybackend.summary.models import Function, MethodCluster


def _function(index: int) -> Function:
    return Function(
        func_id=index,
        func_name=f"f{index}",
        func_desc=f"function {index}",
        func_file="module.py",
        func_flow="",
        func_notf="",
        func_code="pass",
        func_fullName=f"module.f{index}()",
        func_txt_vector=[float(index), 1.0],
    )


class ClusteringContextTest(unittest.TestCase):
    def test_function_adjacency_is_explicit_per_clustering_run(self):
        cluster = MethodCluster(1, "", [_function(0), _function(1)])
        disconnected = compute_link(cluster, np.zeros((2, 2), dtype=int))
        connected = compute_link(cluster, np.array([[0, 1], [0, 0]], dtype=int))

        np.testing.assert_array_equal(disconnected, np.zeros((2, 2)))
        np.testing.assert_array_equal(connected, np.array([[0, 1], [1, 0]]))

    def test_function_adjacency_must_be_square(self):
        cluster = MethodCluster(1, "", [_function(0)])
        with self.assertRaisesRegex(ValueError, "square matrix"):
            compute_link(cluster, np.zeros((1, 2), dtype=int))


if __name__ == "__main__":
    unittest.main()
