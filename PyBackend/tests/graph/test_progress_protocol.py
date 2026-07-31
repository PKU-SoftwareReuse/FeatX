from featx_pybackend.graph import focus, java_rank


def test_focus_progress_emits_a_key_and_structured_arguments(capsys):
    events = []

    with focus.progress_callback(events.append):
        focus._progress("feature-retrieval", 3, featureCount=12, queryLength=48)

    assert events == [{
        "stage": "feature-retrieval",
        "messageKey": "progress.operation.feature-retrieval",
        "messageArgs": {"featureCount": 12, "queryLength": 48},
        "step": 3,
        "total": 8,
    }]
    assert "message" not in events[0]
    capsys.readouterr()


def test_java_progress_uses_the_same_protocol(capsys):
    events = []

    with java_rank.progress_callback(events.append):
        java_rank._progress("graph-ranking", 6, expandedNodeCount=30)

    assert events[0]["messageKey"] == "progress.operation.graph-ranking"
    assert events[0]["messageArgs"] == {"expandedNodeCount": 30}
    assert "message" not in events[0]
    capsys.readouterr()
