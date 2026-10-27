// #425: after a template-only edit (invocation 4) the generated class has the new text.
def generated = new File(basedir, 'target/generated-sources/annotations/com/example/templates')
def sources = []
generated.eachFileRecurse { if (it.name.endsWith('.java') && it.text.contains('version ')) sources << it }
assert sources.size() == 1 : "one generated template class in ${generated}: ${sources}"
def text = sources[0].text
assert text.contains('version three') && !text.contains('version two') : "stale generated class ${sources[0]}"
assert new File(basedir, 'target/classes/META-INF/tessera/templates.fingerprint').isFile()

// build.log has every invocation: the plugin saw both edits (2, 4) and the missing record after the
// failed build (3), and kept the output when nothing changed (5).
def log = new File(basedir, 'build.log').text
assert log.count('Tessera: templates changed since the last compile') == 2
assert log.count('Tessera: no template fingerprint') == 1
def last = log.substring(log.lastIndexOf('Tessera: templates changed'))
assert last.contains('Nothing to compile') : 'invocation 5 recompiled'
return true
