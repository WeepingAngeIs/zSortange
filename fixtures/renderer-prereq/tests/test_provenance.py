import hashlib,io,json,pathlib,sys,tomllib,unittest,zipfile
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]));import provenance as p

def jar(members):
    b=io.BytesIO()
    with zipfile.ZipFile(b,'w') as z:
        for k,v in members.items():z.writestr(k,v)
    return b.getvalue()
class Checks(unittest.TestCase):
    def test_urls(self):
        for u in ['http://cdn.modrinth.com/a','https://cdn.modrinth.com.evil.test/a','https://x@cdn.modrinth.com/a','file:///etc/passwd','https://127.0.0.1/a','https://api.modrinth.com:444/a']:
            with self.subTest(u=u):self.assertFalse(p.url_ok(u))
        self.assertTrue(p.url_ok('https://cdn.modrinth.com/data/a.jar'))
    def test_hashes(self):
        b=b'fixture';s={'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest(),'filename':'x'};pub={x:hashlib.new(x,b).hexdigest() for x in ('sha1','sha512')}
        self.assertEqual(p.check_bytes(b,s,pub)['sha256'],s['sha256'])
        for part in ('sha256','bytes'):
            wrong=s.copy();wrong[part]='0'*64 if part=='sha256' else 0
            with self.assertRaises(p.Refusal):p.check_bytes(b,wrong,pub)
        with self.assertRaises(p.Refusal):p.check_bytes(b,s,{'sha1':'wrong','sha512':pub['sha512']})
    def test_nested_candidates_not_resolution(self):
        nested=jar({'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="fabric_renderer_api_v1"\nversion="3.4.1"\n'})
        b=jar({'META-INF/jarjar/a.jar':nested,'META-INF/jarjar/metadata.json':json.dumps({'jars':[{'path':'META-INF/jarjar/a.jar'}]})})
        rows=p.inventory(b,'wrapper.jar');self.assertEqual(len(rows),2);self.assertEqual(rows[1]['mods'][0]['version'],'3.4.1');self.assertNotIn('selected',rows[1])
    def test_unsafe_member(self):
        for name in ('../bad.jar','/bad.jar'):
            with self.assertRaises(p.Refusal):p.inventory(jar({name:b'x'}),'fixture')
    def test_approval_ce_stays_blocked(self):
        spec=json.loads((p.ROOT/'inputs.json').read_text());self.assertFalse(spec['ce']['execution_approved']);self.assertEqual(spec['ce']['source'],'ad2e7ee5d73e4ff78bd0befade4f797265e65939')
        self.assertEqual([a['mod_version'] for a in spec['artifacts']],['0.8.13+mc1.21.1','1.8.14-beta.1+mc1.21.1','15.0.140'])
        self.assertFalse(spec['iris_options']['enableShaders'])
    def test_no_ce_execution_in_baseline(self):
        text=(p.ROOT/'run_baseline.py').read_text();self.assertNotIn('tools/verify.py',text);self.assertIn("SPEC['ce']['execution_approved'] is False",text)
if __name__=='__main__':unittest.main(verbosity=2)
