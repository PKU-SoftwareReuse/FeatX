import unittest
from unittest.mock import Mock, patch

from src.write2database import insert_row, write_project_summary


class WriteToDatabaseTest(unittest.TestCase):
    @patch("src.write2database.get_or_create_code_map")
    @patch("src.write2database.get_or_create_feature", return_value=22)
    @patch("src.write2database.get_or_create_module", return_value=11)
    def test_insert_row_maps_english_and_chinese_descriptions(
        self,
        get_module,
        get_feature,
        get_code_map,
    ):
        cursor = Mock()
        row = {
            "cluster_id": "3",
            "module_desc": "Comment management",
            "module_desc_cn": "系统管理评论",
            "id": "7",
            "desc": "Administrators review comments",
            "desc_cn": "管理员审核评论",
            "method_name": "a.A.review()",
        }

        insert_row(row, cursor, "12")

        get_module.assert_called_once_with(
            cursor, "12", "3", "Comment management", "系统管理评论"
        )
        get_feature.assert_called_once_with(
            cursor, 11, "7", "Administrators review comments", "管理员审核评论"
        )
        get_code_map.assert_called_once_with(cursor, 22, "a.A.review()")

    @patch("src.write2database.set_finish")
    @patch("src.write2database.save_edges")
    @patch("src.write2database.save_features", side_effect=RuntimeError("invalid CSV"))
    @patch("src.write2database.clear_project_data")
    def test_write_failure_rolls_back_cleared_project_data(
        self,
        clear_project_data,
        save_features,
        save_edges,
        set_finish,
    ):
        connection = Mock()
        cursor = connection.cursor.return_value

        with self.assertRaisesRegex(RuntimeError, "invalid CSV"):
            write_project_summary("12", connection)

        clear_project_data.assert_called_once_with(cursor, "12")
        save_features.assert_called_once_with("12", cursor)
        save_edges.assert_not_called()
        set_finish.assert_not_called()
        connection.commit.assert_not_called()
        connection.rollback.assert_called_once_with()
        cursor.close.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
