"""A minimal SMTP sink on localhost:2525 for apps whose pages send mail (start it in app.json)."""
import socketserver


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b"220 localhost SMTP sink\r\n")
        data = False
        for line in self.rfile:
            if data:
                if line.rstrip(b"\r\n") == b".":
                    data = False
                    self.wfile.write(b"250 OK\r\n")
                continue
            cmd = line[:4].upper()
            if cmd == b"DATA":
                data = True
                self.wfile.write(b"354 go ahead\r\n")
            elif cmd == b"QUIT":
                self.wfile.write(b"221 bye\r\n")
                return
            elif cmd == b"EHLO":
                self.wfile.write(b"250 localhost\r\n")
            else:
                self.wfile.write(b"250 OK\r\n")


socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer(("127.0.0.1", 2525), Handler).serve_forever()
