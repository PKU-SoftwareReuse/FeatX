import sys

from src import RepoSummary, write2database

if __name__ == '__main__':
    if len(sys.argv) == 2:
        project_id = sys.argv[1]
    else:
        project_id = "3"

    RepoSummary.main(project_id)
    write2database.main(project_id)

    # # for project_id in ["8", "12", "13", "14", "15"]:
    # for project_id in ["17"]:
    #     print(project_id)
    #     RepoSummary.main(project_id)
    #     write2database.main(project_id)
