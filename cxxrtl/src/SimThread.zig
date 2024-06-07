const std = @import("std");

const Cxxrtl = @import("./Cxxrtl.zig");

const SimThread = @This();

thread: std.Thread,
vcd_out: ?[]const u8,
mutex: std.Thread.Mutex = .{},
running: bool = true,

pub fn start(vcd_out: ?[]const u8) !*SimThread {
    var sim_thread = try std.heap.c_allocator.create(SimThread);
    sim_thread.* = .{
        .thread = undefined,
        .vcd_out = vcd_out,
    };
    const thread = try std.Thread.spawn(.{}, run, .{sim_thread});
    sim_thread.thread = thread;
    return sim_thread;
}

pub fn lockIfRunning(self: *SimThread) bool {
    self.lock();
    if (!self.running) {
        self.unlock();
        return false;
    }
    return true;
}

fn lock(self: *SimThread) void {
    self.mutex.lock();
}

pub fn unlock(self: *SimThread) void {
    self.mutex.unlock();
}

pub fn halt(self: *SimThread) void {
    self.running = false;
}

fn run(sim_thread: *SimThread) void {
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    defer _ = gpa.deinit();
    const alloc = gpa.allocator();

    var state = State.init(alloc, sim_thread);
    defer state.deinit();

    state.run() catch std.debug.panic("SimThread.State.run threw", .{});
}

const State = struct {
    sim_thread: *SimThread,
    alloc: std.mem.Allocator,

    cxxrtl: Cxxrtl,
    vcd: ?Cxxrtl.Vcd,

    fn init(alloc: std.mem.Allocator, sim_thread: *SimThread) State {
        const cxxrtl = Cxxrtl.init();

        var vcd: ?Cxxrtl.Vcd = null;
        if (sim_thread.vcd_out != null) vcd = Cxxrtl.Vcd.init(cxxrtl);

        return .{ .sim_thread = sim_thread, .alloc = alloc, .cxxrtl = cxxrtl, .vcd = vcd };
    }

    fn deinit(self: *State) void {
        if (self.vcd) |*vcd| vcd.deinit();
        self.cxxrtl.deinit();
    }

    fn run(self: *State) !void {
        const clock = self.cxxrtl.get(bool, "clock");

        // TODO: reset.

        while (self.sim_thread.lockIfRunning()) {
            defer self.sim_thread.unlock();

            clock.next(true);
            self.cxxrtl.step();
            if (self.vcd) |*vcd| vcd.sample();

            clock.next(false);
            self.cxxrtl.step();
            if (self.vcd) |*vcd| vcd.sample();
        }

        if (self.vcd) |*vcd| {
            const buffer = try vcd.read(self.alloc);
            defer self.alloc.free(buffer);

            var file = try std.fs.cwd().createFile(self.sim_thread.vcd_out.?, .{});
            defer file.close();

            try file.writeAll(buffer);
        }
    }
};
