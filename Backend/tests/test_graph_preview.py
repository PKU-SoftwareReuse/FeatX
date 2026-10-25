from copy import deepcopy
from unittest import TestCase
from featx.graph_preview import build_preview


def summary():
    return {'projectId':'a'*32,'runId':'summary-example','epics':[
        {'id':'users','description':'用户身份认证','features':[
            {'id':'login','description':'用户登录密码验证','codeElements':['src/auth.py::login','src/auth.py::verifyPassword','src/user.py::findUser']},
            {'id':'register','description':'用户注册密码校验','codeElements':['src/auth.py::verifyPassword','src/user.py::register','src/user.py::validateUser']},
            {'id':'token','description':'用户登录令牌验证','codeElements':['src/token.py::createToken','src/token.py::verifyToken']},
            {'id':'reset','description':'用户密码重置验证','codeElements':['src/reset.py::resetPassword','src/reset.py::sendCode']}]},
        {'id':'orders','description':'商品订单管理','features':[{'id':'order','description':'订单付款结算','codeElements':['src/order.py::pay']}]}
    ]}


class GraphPreviewTests(TestCase):
    def test_add_three_features_deduplicates_and_prunes_without_mutation(self):
        source=summary();before=deepcopy(source)
        result=build_preview(source,'add','用户登录注册密码验证',epic_id='users')
        self.assertEqual(3,result['relatedFeatureCount'])
        self.assertEqual(0,result['stages']['initial']['elementCount'])
        expanded=result['stages']['expanded']; final=result['stages']['pruned']
        ids=[item['id'] for node in expanded['nodes'] for item in node['elements']]
        self.assertEqual(len(ids),len(set(ids)))
        self.assertEqual(min(12,(len(ids)+1)//2),final['elementCount'])
        self.assertTrue(set(result['keptElementIds']).issubset(ids))
        self.assertEqual(source,before)
        for stage in result['stages'].values(): self.assertEqual([],stage['edges'])

    def test_modify_retains_original_in_expansion_and_adds_two_other_features(self):
        result=build_preview(summary(),'modify','用户登录令牌和密码验证',feature_id='login')
        self.assertEqual(2,result['relatedFeatureCount'])
        self.assertEqual('current',result['features'][0]['origin'])
        self.assertEqual(3,result['stages']['initial']['elementCount'])
        self.assertEqual(3,len({feature['featureId'] for feature in result['features']}))
        initial={item['id'] for node in result['stages']['initial']['nodes'] for item in node['elements']}
        expanded={item['id'] for node in result['stages']['expanded']['nodes'] for item in node['elements']}
        self.assertTrue(initial.issubset(expanded))
        self.assertEqual(result,build_preview(summary(),'modify','用户登录令牌和密码验证',feature_id='login'))

    def test_no_match_uses_selected_epic_and_single_element_is_kept(self):
        result=build_preview(summary(),'add','火星矿物开采',epic_id='users')
        self.assertEqual(3,result['relatedFeatureCount'])
        self.assertTrue(all(f['epicId'] == 'users' for f in result['features']))
        self.assertTrue(result['stages']['pruned']['nodes'])
        self.assertEqual([],result['notices'])
        empty = summary()
        empty['epics'][0]['features'] = []
        result=build_preview(empty,'add','火星矿物开采',epic_id='users')
        self.assertEqual(0,result['relatedFeatureCount'])
        self.assertEqual([],result['stages']['pruned']['nodes'])
        one={'projectId':'a'*32,'runId':'s','epics':[{'id':'e','description':'订单','features':[{'id':'f','description':'订单付款','codeElements':['order.py::pay']}]}]}
        result=build_preview(one,'modify','订单付款',feature_id='f')
        self.assertEqual(1,result['stages']['pruned']['elementCount'])
        self.assertEqual([],result['removedElementIds'])

    def test_modify_retrieval_uses_new_description(self):
        result=build_preview(summary(),'modify','订单付款结算',feature_id='login')
        self.assertEqual('current',result['features'][0]['origin'])
        self.assertEqual('order',result['features'][1]['featureId'])
