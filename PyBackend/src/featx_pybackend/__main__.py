import sys

from .storage import database
from .summary import repository, translation


def main() -> None:
    if len(sys.argv) == 2:
        project_id = sys.argv[1]
    else:
        project_id = "3"

    repository.main(project_id)
    print("[reposummary-main] Translating English summaries to Chinese", flush=True)
    translated_count = translation.main(project_id)
    print(
        f"[reposummary-main] Chinese translation complete ({translated_count} unique summaries)",
        flush=True,
    )
    print("[reposummary-main] Writing summary to database", flush=True)
    database.main(project_id)
    print("[reposummary-main] Database write complete", flush=True)

    # # for project_id in ["8", "12", "13", "14", "15"]:
    # for project_id in ["17"]:
    #     print(project_id)
    #     repository.main(project_id)
    #     database.main(project_id)


if __name__ == "__main__":
    main()
