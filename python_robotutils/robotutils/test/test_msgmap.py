#
# Unit tests for the msgmap module
# by JMJ - a port of the JUnit test for the corresponding Java
# robotutils class, StructuredMessageMap.
#

import unittest
import robotutils.msgmap as msgmap
import random

class TestStringMethods(unittest.TestCase):


    def test_empty_str_to_dict(self):
        d = msgmap.str_to_dict('')
        self.assertEqual(len(d), 0)


    def test_empty_dict_to_str(self):
        s = msgmap.dict_to_str(dict())
        self.assertEqual(len(s), 0)


    def test_singleton_str_to_dict(self):
        d = msgmap.str_to_dict('k:v')
        self.assertEqual(len(d),1)
        self.assertEqual(d.get('k'), 'v')

    def test_singleton_dict_to_str(self):
        s = msgmap.dict_to_str({'k':'v'})
        self.assertEqual(s, 'k:v')

    def test_simple_str_to_dict(self):
        d = msgmap.str_to_dict('k1:v1 k2:v2 k3:v3')
        self.assertEqual(len(d), 3)
        self.assertEqual(d.get('k1'), 'v1')
        self.assertEqual(d.get('k2'), 'v2')
        self.assertEqual(d.get('k3'), 'v3')
        
    def test_simple_dict_to_str(self):
        s = msgmap.dict_to_str({'k1':'v1', 'k2':'v2', 'k3':'v3'})
        self.assertEqual(s, 'k1:v1 k2:v2 k3:v3')

    def test_more_complex_mappings(self):
        '''
            This test creates some crazy key-value pairs with random amounts of
            whitespace between them, and verifies that str-to-dict works in both
            directions.
        '''
        keys = [
                    "!#!@AB89.[],/-+",
                    "2f0j20j0j",
                    "13r2ffs,,,,,,",
                    "-n339ghhss8898v",
                    "d"
        ];
        
        values = [
                    "13r \n\t \r doo bE B",
                    "waka waka waka",
                    "What's all the fuss?!",
                    "(a=3.5, b=4.5, c=11.2)",
                    "\"some quoted string\""
        ];
  
        #keys  = ['a', 'b', 'c']
        #values = ['1', '2', '3']
        
        assert len(keys) == len(values)
        kv_dirty  = [] # includes randome whitespace
        kv_clean = []  # does not include any whitespace
        pre = ''
        for (k,v) in zip(keys, values):
                # make sure our keys and values have no edge whitespaces. We
                # depend on this so that when we convert from dict to str we
                # get exactly what we predict.
                assert k == k.strip()
                assert v == v.strip()

                # This is to ensure that there is a space between each value and
                # subsequent key
                pk = pre + k
                pre = ' '

                # note: random_whitespace may return an empty string
                kv_dirty.append(random_whitespace())
                kv_clean.append(pk)
                kv_dirty.append(pk)

                kv_dirty.append(random_whitespace())
                kv_clean.append(':')
                kv_dirty.append(':')

                kv_dirty.append(random_whitespace())
                kv_clean.append(v)
                kv_dirty.append(v)

                kv_dirty.append(random_whitespace())

        msg_dirty = ''.join(kv_dirty)
        msg_clean = ''.join(kv_clean)

        # create a dictionary usig the 'dirty' meessage - which has random
        # whitespace inserted -- and verify that all the k:v mappings are there
        d = msgmap.str_to_dict(msg_dirty)
        for (k,v) in zip(keys, values):
            self.assertEqual(v, d.get(k))

        # now convert back to a string and verify it is what we expect - the
        # clean string!
        output_msg = msgmap.dict_to_str(d)
        self.assertEqual(output_msg, msg_clean)

def random_whitespace():
    whitespace = '    \t\t    \n\r      '
    i = random.randrange(len(whitespace))
    return whitespace[i:]
    
        
# The following seems to have no purpose here, because
# these tests are living at or below the robotutils package directory
# if __name__ == '__main__':
#    unittest.main()
        

'''
        
        assert(keys.length == values.length);
        
        StringBuilder sb = new StringBuilder();
        StringBuilder sbClean = new StringBuilder();
        String pre = "";
        for (int i = 0; i< keys.length; i++) {
            String k = keys[i];
            String v = values[i];
            // Make sure our test data is clean - no edge whitespace.
            assert(k.equals(k.trim()));
            assert(v.equals(v.trim()));
            String space = randomWhitespace();
            sb.append(space);
            
            sbClean.append(pre);
            sbClean.append(k);
            pre = " ";
            sb.append(k);
            space = randomWhitespace();
            sb.append(space);
            
            sb.append(":");
            sbClean.append(":");
            
            space = randomWhitespace();
            sb.append(space);
            
            sb.append(v);
            sbClean.append(v);
            
            space = randomWhitespace();
            sb.append(space);
        }
        String input = sb.toString();
        String cleanInput = sbClean.toString();
        
        HashMap<String, String> map = StructuredMessageMapper.toHashMap(input);
        
        // Verify map contents.
        for (int i=0; i<keys.length; i++) {
            String k = keys[i];
            String v = values[i];
            assertEquals(map.get(k), v);
        }
        
        // Convert back to string
        String outputMessage = StructuredMessageMapper.toString(map, keys);
        assertEquals(outputMessage, cleanInput);
    }
    
    // Raturns a random amount of "random" whitespace.
    String randomWhitespace() {
        String randomWhitespace = "    \t\t    \n\r      ";
        return randomWhitespace.substring((int)(Math.random()*randomWhitespace.length()));
    }
'''
