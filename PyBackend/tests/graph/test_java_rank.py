from featx_pybackend.graph.java_rank import rank_graph, retrieve_features


def test_modify_retrieval_forces_current_feature_to_rank_one():
    request = {
        "operation": "modify",
        "featureId": "2",
        "query": "print a greeting",
        "topKFeatures": 2,
        "features": [
            {"featureId": "1", "moduleDesc": "jobs", "description": "print output", "methods": ["A.run()"]},
            {"featureId": "2", "moduleDesc": "jobs", "description": "parse input", "methods": ["B.parse()"]},
            {"featureId": "3", "moduleDesc": "config", "description": "load config", "methods": ["C.load()"]},
        ],
    }

    result = retrieve_features(request, score_fn=lambda _query, _docs: [0.9, 0.1, 0.2])

    assert [feature["featureId"] for feature in result["selectedFeatures"]] == ["2", "1"]
    assert result["selectedFeatures"][0]["reason"] == "forced_current_feature"
    assert result["seedMethods"] == ["B.parse()", "A.run()"]


def test_rank_graph_uses_method_boosts_and_returns_induced_top_k_nodes():
    request = {
        "query": "create an order",
        "topKNodes": 2,
        "nodes": [
            {
                "id": "OrderService",
                "category": "Class",
                "text": "class OrderService",
                "seed": False,
            },
            {
                "id": "OrderService.create()",
                "category": "Method",
                "text": "void create() {}",
                "seed": True,
                "currentFeature": True,
                "selectedFeatureScore": 0.8,
            },
            {
                "id": "Audit.log()",
                "category": "Method",
                "text": "void log() {}",
                "seed": False,
                "currentFeature": False,
                "selectedFeatureScore": 0.0,
            },
        ],
        "edges": [
            {"from": "OrderService", "to": "OrderService.create()", "type": "MethodMember"},
            {"from": "OrderService.create()", "to": "Audit.log()", "type": "CallArc"},
        ],
    }

    result = rank_graph(request, score_fn=lambda _query, _docs: [0.2, 0.9, 0.1])

    assert len(result["selectedNodeIds"]) == 2
    assert "OrderService.create()" in result["selectedNodeIds"]
    assert result["baseScores"]["OrderService.create()"] > result["baseScores"]["Audit.log()"]
    assert abs(sum(result["rankScores"].values()) - 1.0) < 1.0e-6
