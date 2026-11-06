"""接口字段命名及文档约束。"""
import re
import unittest
from scripts.openapi_contract import document

class APIFieldContractTests(unittest.TestCase):
    def test_json_properties_have_snake_case_names_and_descriptions(self):
        def check(model):
            for name, definition in model.get('properties',{}).items():
                self.assertRegex(name,r'^[a-z][a-z0-9_]*$')
                self.assertTrue(definition.get('description','').strip(),name)
                self.assertLessEqual(len(definition['description']),80,name)
                check(definition)
            if isinstance(model.get('items'),dict): check(model['items'])
            for definition in model.get('allOf',[]): check(definition)
        for model in document()['components']['schemas'].values(): check(model)

    def test_path_and_query_parameter_names_match_paths(self):
        doc = document()
        for path, item in doc['paths'].items():
            for op in item.values():
                if not isinstance(op,dict) or 'operationId' not in op: continue
                names = set()
                for parameter in op.get('parameters',[]):
                    if '$ref' in parameter:
                        parameter=doc['components']['parameters'][parameter['$ref'].rsplit('/',1)[1]]
                    if parameter['in'] not in ('path','query'): continue
                    self.assertRegex(parameter['name'],r'^[a-z][a-z0-9_]*$')
                    if parameter['in']=='path': names.add(parameter['name'])
                self.assertEqual(set(re.findall(r'\{([^}]+)\}',path)),names)

if __name__=='__main__': unittest.main()
