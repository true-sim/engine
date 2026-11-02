// The particle simulation's ray-query variant (effects.md §2.8): simulate.slang's entry points, each
// [shader("compute")] as there, with ray collision (collide.rays) tracing into the frame's top level; used on devices
// with ray tracing for the simulations given one. (The build compiles files that name [shader(...)] entry points:
// hence this comment's.)
#define PARTICLE_RAYS 1
#include "simulate.slang"
