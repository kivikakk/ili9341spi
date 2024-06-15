import contextlib

from amaranth.lib import stream


class InterfaceExtensions:
    def deq(self, m):
        m.d.comb += self.ready.eq(1)
        return self.payload

    @property
    def fire(self):
        return self.valid & self.ready

    @contextlib.contextmanager
    def Deq(self, m):
        payload = self.deq(m)
        with m.If(self.fire):
            yield payload

    def enq(self, m, payload):
        m.d.comb += [
            self.valid.eq(1),
            self.payload.eq(payload),
        ]


for k, v in vars(InterfaceExtensions).items():
    if not k.startswith("_"):
        setattr(stream.Interface, k, v)
