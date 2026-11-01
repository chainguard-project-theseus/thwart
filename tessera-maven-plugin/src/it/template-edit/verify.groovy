import java.security.MessageDigest

// #425: after a template-only edit (invocation 4) the generated class has the new text.
def generated = new File(basedir, 'target/generated-sources/annotations/com/example/templates')
def sources = []
generated.eachFileRecurse { if (it.name.endsWith('.java') && it.text.contains('version ')) sources << it }
assert sources.size() == 1 : "one generated template class in ${generated}: ${sources}"
def text = sources[0].text
assert text.contains('version three') && !text.contains('version two') : "stale generated class ${sources[0]}"

// #447: the record covers the message files as they are after the last edit (invocation 8).
def fingerprint = new File(basedir, 'target/classes/META-INF/tessera/templates.fingerprint').text
def sha = { f -> MessageDigest.getInstance('SHA-256').digest(new File(basedir, f).bytes).encodeHex().toString() }
assert fingerprint.startsWith('tessera-templates 2\n') : fingerprint
assert fingerprint.contains('messages messages\n') : fingerprint
assert fingerprint.contains(sha('src/main/resources/messages.properties') + ' messages.properties\n') : fingerprint
assert fingerprint.contains(sha('src/main/resources/messages_fr.properties') + ' messages_fr.properties\n') : fingerprint

// #626: the file another plugin wrote before the first compile (invocation 1) was kept.
assert new File(basedir, 'target/classes/git.properties').text.contains('git.commit.id=') : 'git.properties deleted'

// build.log has every invocation: the plugin saw the template edits (2, 4) and the message edits
// (6, 7), and kept the output when nothing changed (5, 9). A failed build (2, 7) leaves no class
// files and no record, so the next one (3, 8) compiles without the plugin deleting anything, as
// does the first (1), whose class output holds only git.properties (#626). Invocation 7 failed on
// the locale file's markup.
def log = new File(basedir, 'build.log').text
assert log.count('Tessera: templates or messages changed since the last compile') == 4
assert log.count('Tessera: no template fingerprint') == 0
assert log.count('Nothing to compile') == 2 : 'invocation 5 or 9 recompiled'
assert log.contains("message 'greeting' in ") && log.contains('messages_fr.properties') && log.contains('<img> is not allowed')
assert !log.contains('read other message bundles')
def last = log.substring(log.lastIndexOf('edits/msg-fixed'))
assert last.contains('Recompiling the module') : 'invocation 8 did not compile'
assert last.substring(last.indexOf('Recompiling the module')).contains('Nothing to compile') : 'invocation 9 recompiled'
// #428: the review goal ran after compile (invocation 9) and wrote its report.
def review = new File(basedir, 'target/tessera-review.txt').text
assert review.contains('## @TemplateFunctions (0)\n') && review.contains('calls (0)\n') : review
assert log.contains('Tessera: 0 @TemplateFunction(s), 0 returning a safe type, and 0 unchecked call(s)')
return true
