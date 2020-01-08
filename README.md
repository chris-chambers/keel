# Keel

Low-level Kubernetes Resource Manipulation

## Design
   - Keel is a low-level tool.  It is meant to be used by other tools.  It should
     not prescribe too much about the manner of its use.
   - By default, it produces machine-readable output (JSON), rather than human-
     friendly output.
## Definitions
<dl>
  <dt>ref</dt>
  <dd>a selfLink-like string that refers to one or more resources</dd>

  <dt>start state</dt>
  <dd>the ideal (possibly inaccurate) beginning state for a set of resources
  that keel will be dealing with.  in most cases, this is the previous target
  state (nil if this is the first operation).</dd>

  <dt>target state</dt>
  <dd>the set of resources defining the desired end state for an operation.</dd>

  <dt>live state</dt>
  <dd>the current state of the union of resources given by the start and target
  states.</dd>

  <dt>waybill</dt>
  <dd>the input to `keel plan`, which specifies the start and target states, and
  optionally a theoretical live state.</dd>

  <dt>plan</dt>
  <dd>the input to `keel apply`, which includes all information needed to put
  the set of resources into the target state. (includes start, target and live
  states)</dd>
</dl>


## Commands

### `keel get [--watch]`
`keel get` is a little like `kubectl get`, with some notable differences:

- Keel's way of referring to Kubernetes resources is more verbose (but, more
  flexible and precise) then kubectl's.
- `keel get` can query an arbitrary resource set, across namespaces and API
  groups, all in one execution.
- There are no restrictions on `keel get --watch`.  Any set of refs that can be
  passed to `keel get` will work with the `--watch` flag.
- `keel get` always returns a stream of objects, unlike kubectl's behavior of
  returning a synthetic list in normal mode and a stream in `--watch` mode.
- `keel get --watch` preserves the action type (eg, ADDED) so users can react
  to different events in different ways, if desired.

### `keel collect`
`keel collect` takes lists of files and folders and produces a waybill
suitable for passing to `keel plan`.  By default it also captures the current
live state, and includes it in the waybill.  But, this is optional, and if
omitted, `keel plan` will capture a copy of the live state when it is
invoked.

### `keel plan`

`keel plan` takes a source state, target state and live state (either pass or
automatically retrieved from the current cluster), and produces a plan that is
suitable for passing to `keel apply`.

- The order of items in the waybill defines the order of application in the
  produced plan.  (Things are added in order and removed in reverse order).
- Items in the waybill are classified according to the action that needs to be
  taken on them to bring them up-to-date.
- Classifications are `create`, `annex`, `restore`, `update`, `delete` and `gone`
- `create`, `update`, and `delete` have the usual semantics.
- `annex` indicates that an existing, unknown (not given in the start state)
  resource will be taken over.  Annexation is destructive, so users will need
  to approve this explicitly (TODO: how will they communicate approval?)
- `restore` means that a resource that is present in both start and target has
  gone missing from the live state, and will be put back.
- `gone` means that a resource was present in the start state and absent from
  the target state.  So, it would ordinarily be deleted, but it is also
  absent from the live state, which means there is nothing to do.

### `keel apply`
`keel apply` executes a plan produced by `keel plan` (or by some other means).

- a plan is just a list of `{ action, item }` maps, where action is one of the
  classifications from `keel plan` (create/annex/restore/update/delete/gone),
  and item is the resource to be applied.
- the order of the items in the list is the order of application.
- `keel apply` emits a stream of results it goes which represent the actual
  actions taken.
- The output of `keel apply` can be passed to `keel reverse` to directly
  produce a plan that undoes the work.
- The output of `keel apply` can also be passed to `keel stable` to determine
  if all the modifications in the apply operation have reached a stable state.

### `keel reverse`
`keel reverse` takes the output of a `keel apply` invocation and creates a new
plan that reverses the effect of the apply.

(NOTE: Not yet implemented)

### `keel stable`
`keel stable` takes the output of a `keel apply` invocation and checks all the
resources until they reach stability, then returns.

(NOTE: Not yet implemented)

- Can this run in `--watch` mode as well as one-shot?
- Should accept a live state _or_ be able to query for it.
- NOTE: it can't really just take the target list.  what about resources that
  are transitioning to `gone`?  they need some kind of special handling.
- checks if all items in the current state snapshot list are stable
  with respect to the target list (right number of replicas, pods healthy,
  etc)
- returns two lists: stable resources and unstable resources

# Tender

A higher-level tool built on Keel, similar to [Helm](https://helm.sh), but
without built-in templating, and with the stronger deployment guarantees that
Keel makes possible.  Templating is left to external tools so users can choose
whatever templating system they are most comfortable with.  (TODO: Is it
important to provide some kind of uniformity so users can easily share with each
other?)


## Design
Always operate from a cache, with the ability to demand synchronization for
critical resources.

All functionality should be completely independent, specifying a contract that
other programs could satisfy, if they desire.

### Commands

### `tender deploy`
FIXME: the idea about ordering templates, grouping them, saving state, so it's
resumable, eventually timing out (maybe), didn't make it into this documentation.

- takes start and target state (gets start state from configmap by default)
- calculates watch set
- starts dingy get --watch
- runs keel apply
- until keel stable: wait for dingy get --watch change

### `tender delete`
- same logic as tender deploy, except the target state is nil

### `tender wait`
TODO: is this necessary as a separate command, if we make `tender deploy` and
      `tender delete` idempotent, and include a `--wait` flag?

- operates on a target list (wrong: see note at tender stable)
- starts tender watch and maintains a cache of all interesting (relevant to
  the target list) resources in the cluster
- calls tender stable when the resources have changed (maybe with debounce or
  other strategies)
- when all resources have been stable long enough (user-configurable, like
  liveness/readiness checks), stop with success
- when resources haven't become stable before a timeout, die

## Questions
How do we deal with CRDs that may not have been applied yet?  Vela will need
to recognize urls whose API resource definitions are not yet present in the
cluster.  The only way I can see is to examine all resources being applied,
find CRDs and generate the definitions client-side.  Then, when watching:
- try to watch the custom resources requested (which might fail)
- if it fails, watch the CRDs themselves, until a necessary one is created
- at that point, start to watch for custom resources of that kind

Can/should tender understand the semantics of ConfigMaps/Secrets that are
mounted vs read into environment variables?  Spinnaker takes the always-safe-
always-annoying stance that these kinds of resources should always be
versioned.  There are benefits to both kinds of semantics.  Maybe some Pods
_prefer_ to watch the file sytem for changes.  This should be controlled by
an annotation.

Can this question be pushed to the templating level?  Tender (maybe?) would
want to enforce that old versions of ConfigMaps/Secrets are eventually
cleaned up.  But, the [... would be nice to know what I was going to write
here.]

How does rollback differ from deploy, exactly?  Users want to be able to say
just `tender rollback` to go back one version.  But, what if they want to go
back farther?  Where are historical manifest sets stored?  Can this be made
generic?  If it is, will it be ugly to use?

Can we atomically apply a heterogenous list of Kubernetes resources?  Is this
even desirable?  Maybe we'll want to wait for stability as we go.

Is this always right for `tender apply`?  Maybe there should be a choice
between LIFO and FIFO.
- the order of the lists defines the order of application:
  - create in list-order of the target list
  - destroy in reverse-list-order of the start list


Use CUE for the data constraint language?  How to embed in the program (it's
written in go)?

# Notes for Experimenters
## Install
     # asdf
     brew install asdf
     # Add to your .bash_profile or equivalent:
     # . $(brew --prefix asdf)/asdf.sh

     # GraalVM
     asdf plugin-add graalvm
     asdf install graalvm 19.2.0.1 # or higher, if available

     # Go (for Kubernetes in Docker)
     brew install go
     go version # expect 1.13+

     # kind (Kubernetes in Docker)
     GO111MODULE="on" go get sigs.k8s.io/kind@v0.5.1
     kind create cluster
     export KUBECONFIG=$(kind get kubeconfig-path)


## Emacs/Cider
   setenv JAVA_HOME to $(asdf where graalvm)
   maybe add this to .dir-locals.el
