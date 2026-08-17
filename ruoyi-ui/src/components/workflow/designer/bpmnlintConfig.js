function getDefaultExportFromCjs (x) {
	return x && x.__esModule && Object.prototype.hasOwnProperty.call(x, 'default') ? x['default'] : x;
}

function getAugmentedNamespace(n) {
  if (Object.prototype.hasOwnProperty.call(n, '__esModule')) return n;
  var f = n.default;
	if (typeof f == "function") {
		var a = function a () {
			var isInstance = false;
      try {
        isInstance = this instanceof a;
      } catch (e) {}
			if (isInstance) {
        return Reflect.construct(f, arguments, this.constructor);
			}
			return f.apply(this, arguments);
		};
		a.prototype = f.prototype;
  } else a = {};
  Object.defineProperty(a, '__esModule', {value: true});
	Object.keys(n).forEach(function (k) {
		var d = Object.getOwnPropertyDescriptor(n, k);
		Object.defineProperty(a, k, d.get ? d : {
			enumerable: true,
			get: function () {
				return n[k];
			}
		});
	});
	return a;
}

/**
 * Checks whether node is of specific bpmn type.
 *
 * @param {ModdleElement} node
 * @param {String} type
 *
 * @return {Boolean}
 */
function is(node, type) {

  if (type.indexOf(':') === -1) {
    type = 'bpmn:' + type;
  }

  return (
    (typeof node.$instanceOf === 'function')
      ? node.$instanceOf(type)
      : node.$type === type
  );
}

/**
 * Checks whether node has any of the specified types.
 *
 * @param {ModdleElement} node
 * @param {Array<String>} types
 *
 * @return {Boolean}
 */
function isAny(node, types) {
  return types.some(function(type) {
    return is(node, type);
  });
}

var index_esm = /*#__PURE__*/Object.freeze({
	__proto__: null,
	is: is,
	isAny: isAny
});

var require$$0 = /*@__PURE__*/getAugmentedNamespace(index_esm);

var helper = {};

var hasRequiredHelper;

function requireHelper () {
	if (hasRequiredHelper) return helper;
	hasRequiredHelper = 1;
	const {
	  is
	} = require$$0;

	/**
	 * @typedef { import('../lib/types.js').ModdleElement } ModdleElement
	 *
	 * @typedef { import('../lib/types.js').RuleFactory } RuleFactory
	 * @typedef { import('../lib/types.js').RuleDefinition } RuleDefinition
	 */


	/**
	 * Create a checker that disallows the given element type.
	 *
	 * @param { string } type
	 *
	 * @return { RuleFactory } ruleFactory
	 */
	function checkDiscouragedNodeType(type, ruleName) {

	  /**
	   * @type { RuleFactory }
	   */
	  return function() {

	    function check(node, reporter) {

	      if (is(node, type)) {
	        reporter.report(node.id, 'Element type <' + type + '> is discouraged');
	      }
	    }

	    return annotateRule(ruleName, {
	      check
	    });

	  };

	}

	helper.checkDiscouragedNodeType = checkDiscouragedNodeType;


	/**
	 * Find a parent for the given element
	 *
	 * @param { ModdleElement } node
	 * @param { string } type
	 *
	 * @return { ModdleElement } element
	 */
	function findParent(node, type) {
	  if (!node) {
	    return null;
	  }

	  const parent = node.$parent;

	  if (!parent) {
	    return node;
	  }

	  if (is(parent, type)) {
	    return parent;
	  }

	  return findParent(parent, type);
	}

	helper.findParent = findParent;


	/**
	 * Check if the node is inside of an executable process.
	 *
	 * @param { ModdleElement } node
	 *
	 * @return { boolean }
	 */
	function isInExecutableProcess(node) {
	  const process = findParent(node, 'bpmn:Process');

	  return process && process.isExecutable;
	}

	helper.isInExecutableProcess = isInExecutableProcess;


	const documentationBaseUrl = 'https://github.com/bpmn-io/bpmnlint/blob/main/docs/rules';

	/**
	 * Annotate a rule with core information, such as the documentation url.
	 *
	 * @param {string} ruleName
	 * @param {RuleDefinition} options
	 *
	 * @return {RuleDefinition}
	 */
	function annotateRule(ruleName, options) {

	  const {
	    meta: {
	      documentation = {},
	      ...restMeta
	    } = {},
	    ...restOptions
	  } = options;

	  const documentationUrl = `${documentationBaseUrl}/${ruleName}.md`;

	  return {
	    meta: {
	      documentation: {
	        url: documentationUrl,
	        ...documentation
	      },
	      ...restMeta
	    },
	    ...restOptions
	  };
	}

	helper.annotateRule = annotateRule;
	return helper;
}

var adHocSubProcess;
var hasRequiredAdHocSubProcess;

function requireAdHocSubProcess () {
	if (hasRequiredAdHocSubProcess) return adHocSubProcess;
	hasRequiredAdHocSubProcess = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that ensures that an Ad Hoc Sub Process is valid according to the BPMN spec:
	 *
	 * - No start or end events
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	adHocSubProcess = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:AdHocSubProcess')) {
	      return;
	    }

	    const flowElements = node.flowElements || [];

	    flowElements.forEach(function(flowElement) {

	      if (is(flowElement, 'bpmn:StartEvent')) {
	        reporter.report(flowElement.id, 'A <Start Event> is not allowed in <Ad Hoc Sub Process>');
	      }

	      if (is(flowElement, 'bpmn:EndEvent')) {
	        reporter.report(flowElement.id, 'An <End Event> is not allowed in <Ad Hoc Sub Process>');
	      }
	    });
	  }

	  return annotateRule('ad-hoc-sub-process', {
	    check
	  });

	};
	return adHocSubProcess;
}

var adHocSubProcessExports = requireAdHocSubProcess();
var rule_0 = /*@__PURE__*/getDefaultExportFromCjs(adHocSubProcessExports);

var conditionalFlows;
var hasRequiredConditionalFlows;

function requireConditionalFlows () {
	if (hasRequiredConditionalFlows) return conditionalFlows;
	hasRequiredConditionalFlows = 1;
	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks that sequence flows outgoing from a
	 * conditional forking gateway or activity are
	 * either default flows _or_ have a condition attached
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	conditionalFlows = function() {

	  function check(node, reporter) {

	    if (!isConditionalForking(node)) {
	      return;
	    }

	    const outgoing = node.outgoing || [];

	    outgoing.forEach((flow) => {
	      const missingCondition = (
	        !hasCondition(flow) &&
	        !isDefaultFlow(node, flow)
	      );

	      if (missingCondition) {
	        reporter.report(flow.id, 'Sequence flow is missing condition', [ 'conditionExpression' ]);
	      }
	    });
	  }

	  return annotateRule('conditional-flows', {
	    check
	  });

	};


	// helpers /////////////////////////////

	function isConditionalForking(node) {

	  const defaultFlow = node['default'];
	  const outgoing = node.outgoing || [];

	  return defaultFlow || outgoing.find(hasCondition);
	}

	function hasCondition(flow) {
	  if (flow.conditionExpression) {
	    return true;
	  }

	  // 条件设计器只保存版本化业务规则，部署时后端才编译为固定路由表达式；lint 必须识别该受控作者配置。
	  const extensionValues = flow.extensionElements && flow.extensionElements.values || [];
	  return extensionValues.some((extension) => {
	    const properties = extension && extension.values || [];
	    return properties.some((property) => property && property.name === 'approva.conditionRule.config');
	  });
	}

	function isDefaultFlow(node, flow) {
	  return node['default'] === flow;
	}
	return conditionalFlows;
}

var conditionalFlowsExports = requireConditionalFlows();
var rule_1 = /*@__PURE__*/getDefaultExportFromCjs(conditionalFlowsExports);

var endEventRequired;
var hasRequiredEndEventRequired;

function requireEndEventRequired () {
	if (hasRequiredEndEventRequired) return endEventRequired;
	hasRequiredEndEventRequired = 1;
	const {
	  is,
	  isAny
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks the presence of an end event per scope.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	endEventRequired = function() {

	  function hasEndEvent(node) {
	    const flowElements = node.flowElements || [];

	    return (
	      flowElements.some(node => is(node, 'bpmn:EndEvent'))
	    );
	  }

	  function check(node, reporter) {

	    if (!isAny(node, [
	      'bpmn:Process',
	      'bpmn:SubProcess'
	    ]) || is(node, 'bpmn:AdHocSubProcess')) {
	      return;
	    }

	    if (!hasEndEvent(node)) {
	      const type = is(node, 'bpmn:SubProcess') ? 'Sub process' : 'Process';

	      reporter.report(node.id, type + ' is missing end event');
	    }
	  }

	  return annotateRule('end-event-required', {
	    check
	  });
	};
	return endEventRequired;
}

var endEventRequiredExports = requireEndEventRequired();
var rule_2 = /*@__PURE__*/getDefaultExportFromCjs(endEventRequiredExports);

var eventBasedGateway;
var hasRequiredEventBasedGateway;

function requireEventBasedGateway () {
	if (hasRequiredEventBasedGateway) return eventBasedGateway;
	hasRequiredEventBasedGateway = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks, whether an event-based gateway:
	 * - has at least two outgoing sequence flows
	 * - the outgoing sequence flows are not conditional
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	eventBasedGateway = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:EventBasedGateway')) {
	      return;
	    }

	    const outgoing = node.outgoing || [];

	    if (outgoing.length < 2) {
	      reporter.report(node.id, 'An <Event-based Gateway> must have at least 2 outgoing <Sequence Flows>');
	    }

	    outgoing.forEach((flow) => {
	      if (hasCondition(flow)) {
	        reporter.report(flow.id, 'A <Sequence Flow> outgoing from an <Event-based Gateway> must not be conditional');
	      }
	    });
	  }

	  return annotateRule('event-based-gateway', {
	    check
	  });
	};

	function hasCondition(flow) {
	  return !!flow.conditionExpression;
	}
	return eventBasedGateway;
}

var eventBasedGatewayExports = requireEventBasedGateway();
var rule_3 = /*@__PURE__*/getDefaultExportFromCjs(eventBasedGatewayExports);

var eventSubProcessTypedStartEvent;
var hasRequiredEventSubProcessTypedStartEvent;

function requireEventSubProcessTypedStartEvent () {
	if (hasRequiredEventSubProcessTypedStartEvent) return eventSubProcessTypedStartEvent;
	hasRequiredEventSubProcessTypedStartEvent = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks that start events inside an event sub-process
	 * are typed.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	eventSubProcessTypedStartEvent = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:SubProcess') || !node.triggeredByEvent) {
	      return;
	    }

	    const flowElements = node.flowElements || [];

	    flowElements.forEach(function(flowElement) {

	      if (!is(flowElement, 'bpmn:StartEvent')) {
	        return false;
	      }

	      const eventDefinitions = flowElement.eventDefinitions || [];

	      if (eventDefinitions.length === 0) {
	        reporter.report(flowElement.id, 'Start event is missing event definition', [ 'eventDefinitions' ]);
	      }
	    });
	  }

	  return annotateRule('event-sub-process-typed-start-event', {
	    check
	  });

	};
	return eventSubProcessTypedStartEvent;
}

var eventSubProcessTypedStartEventExports = requireEventSubProcessTypedStartEvent();
var rule_4 = /*@__PURE__*/getDefaultExportFromCjs(eventSubProcessTypedStartEventExports);

var fakeJoin;
var hasRequiredFakeJoin;

function requireFakeJoin () {
	if (hasRequiredFakeJoin) return fakeJoin;
	hasRequiredFakeJoin = 1;
	const {
	  isAny
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks that no fake join is modeled by attempting
	 * to give a task or event join semantics.
	 *
	 * Users should model a parallel joining gateway
	 * to achieve the desired behavior.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	fakeJoin = function() {

	  function check(node, reporter) {

	    if (!isAny(node, [
	      'bpmn:Activity',
	      'bpmn:Event'
	    ])) {
	      return;
	    }

	    const incoming = node.incoming || [];

	    if (incoming.length > 1) {
	      reporter.report(node.id, 'Incoming flows do not join');
	    }
	  }

	  return annotateRule('fake-join', {
	    check
	  });

	};
	return fakeJoin;
}

var fakeJoinExports = requireFakeJoin();
var rule_5 = /*@__PURE__*/getDefaultExportFromCjs(fakeJoinExports);

var global;
var hasRequiredGlobal;

function requireGlobal () {
	if (hasRequiredGlobal) return global;
	hasRequiredGlobal = 1;
	const {
	  is,
	  isAny
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that verifies that global elements are properly used.
	 *
	 * Currently recognized global elements are:
	 *
	 *   * `bpmn:Error`
	 *   * `bpmn:Escalation`
	 *   * `bpmn:Message`
	 *   * `bpmn:Signal`
	 *
	 * For each of these elements proper usage implies:
	 *
	 *   * element must have a name
	 *   * element is referenced by at least one element
	 *   * there exists only a single element per type with a given name
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	global = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:Definitions')) {
	      return false;
	    }

	    const rootElements = getRootElements(node);

	    const referencingElements = getReferencingElements(node);

	    rootElements.forEach(rootElement => {
	      if (!hasName(rootElement)) {
	        reporter.report(rootElement.id, 'Element is missing name');
	      }

	      if (!isReferenced(rootElement, referencingElements)) {
	        reporter.report(rootElement.id, 'Element is unused');
	      }

	      if (!isUnique(rootElement, rootElements)) {
	        reporter.report(rootElement.id, 'Element name is not unique');
	      }
	    });

	  }

	  return annotateRule('global', {
	    check
	  });

	  // helpers /////////////////////////////

	  function getRootElements(definitions) {
	    return definitions.rootElements.filter(node => isAny(node, [ 'bpmn:Error', 'bpmn:Escalation', 'bpmn:Message', 'bpmn:Signal' ]));
	  }

	  function getReferencingElements(definitions) {
	    const referencingElements = [];

	    function traverse(element) {
	      if (is(element, 'bpmn:Definitions') && element.get('rootElements').length) {
	        element.get('rootElements').forEach(traverse);
	      }

	      if (is(element, 'bpmn:FlowElementsContainer') && element.get('flowElements').length) {
	        element.get('flowElements').forEach(traverse);
	      }

	      if (is(element, 'bpmn:Event') && element.get('eventDefinitions').length) {
	        element.get('eventDefinitions').forEach(eventDefinition => referencingElements.push(eventDefinition));
	      }

	      if (is(element, 'bpmn:Collaboration') && element.get('messageFlows').length) {
	        element.get('messageFlows').forEach(traverse);
	      }

	      if (isAny(element, [
	        'bpmn:MessageFlow',
	        'bpmn:ReceiveTask',
	        'bpmn:SendTask'
	      ])) {
	        referencingElements.push(element);
	      }
	    }

	    traverse(definitions);

	    return referencingElements;
	  }

	  function hasName(event) {
	    return (
	      event.name?.trim() !== ''
	    );
	  }

	  function isReferenced(rootElement, referencingElements) {
	    if (is(rootElement, 'bpmn:Error')) {
	      return referencingElements.some(referencingElement => {
	        return is(referencingElement, 'bpmn:ErrorEventDefinition')
	          && rootElement.get('id') === referencingElement.get('errorRef')?.get('id');
	      });
	    }

	    if (is(rootElement, 'bpmn:Escalation')) {
	      return referencingElements.some(referencingElement => {
	        return is(referencingElement, 'bpmn:EscalationEventDefinition')
	          && rootElement.get('id') === referencingElement.get('escalationRef')?.get('id');
	      });
	    }

	    if (is(rootElement, 'bpmn:Message')) {
	      return referencingElements.some(referencingElement => {
	        return isAny(referencingElement, [
	          'bpmn:MessageEventDefinition',
	          'bpmn:MessageFlow',
	          'bpmn:ReceiveTask',
	          'bpmn:SendTask'
	        ]) && rootElement.get('id') === referencingElement.get('messageRef')?.get('id');
	      });
	    }

	    if (is(rootElement, 'bpmn:Signal')) {
	      return referencingElements.some(referencingElement => {
	        return is(referencingElement, 'bpmn:SignalEventDefinition')
	          && rootElement.get('id') === referencingElement.get('signalRef')?.get('id');
	      });
	    }
	  }

	  function isUnique(rootElement, rootElements) {
	    return (
	      rootElements.filter(otherRootElement => is(otherRootElement, rootElement.$type) && rootElement.name === otherRootElement.name).length === 1
	    );
	  }
	};
	return global;
}

var globalExports = requireGlobal();
var rule_6 = /*@__PURE__*/getDefaultExportFromCjs(globalExports);

var labelRequired;
var hasRequiredLabelRequired;

function requireLabelRequired () {
	if (hasRequiredLabelRequired) return labelRequired;
	hasRequiredLabelRequired = 1;
	const {
	  is,
	  isAny
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks the presence of a label.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	labelRequired = function() {

	  function check(node, reporter) {

	    if (isAny(node, [
	      'bpmn:ParallelGateway',
	      'bpmn:EventBasedGateway'
	    ])) {
	      return;
	    }

	    // ignore joining gateways
	    if (is(node, 'bpmn:Gateway') && !isForking(node)) {
	      return;
	    }

	    // ignore sub-processes
	    if (is(node, 'bpmn:SubProcess')) {

	      // TODO(nikku): better ignore expanded sub-processes only
	      return;
	    }

	    // ignore sequence flow without condition
	    if (is(node, 'bpmn:SequenceFlow') && !hasCondition(node)) {
	      return;
	    }

	    // ignore data objects and artifacts for now
	    if (isAny(node, [
	      'bpmn:FlowNode',
	      'bpmn:SequenceFlow',
	      'bpmn:Participant',
	      'bpmn:Lane'
	    ])) {

	      const name = (node.name || '').trim();

	      if (name.length === 0) {
	        reporter.report(node.id, 'Element is missing label/name', [ 'name' ]);
	      }
	    }
	  }

	  return annotateRule('label-required', {
	    check
	  });
	};


	// helpers ////////////////////////

	function isForking(node) {
	  const outgoing = node.outgoing || [];

	  return outgoing.length > 1;
	}

	function hasCondition(node) {
	  return node.conditionExpression;
	}
	return labelRequired;
}

var labelRequiredExports = requireLabelRequired();
var rule_7 = /*@__PURE__*/getDefaultExportFromCjs(labelRequiredExports);

/**
 * Flatten array, one level deep.
 *
 * @template T
 *
 * @param {T[][] | T[] | null} [arr]
 *
 * @return {T[]}
 */
function flatten(arr) {
  return Array.prototype.concat.apply([], arr);
}

const nativeToString = Object.prototype.toString;
const nativeHasOwnProperty = Object.prototype.hasOwnProperty;

function isUndefined(obj) {
  return obj === undefined;
}

function isDefined(obj) {
  return obj !== undefined;
}

function isNil(obj) {
  return obj == null;
}

function isArray(obj) {
  return nativeToString.call(obj) === '[object Array]';
}

function isObject(obj) {
  return nativeToString.call(obj) === '[object Object]';
}

function isNumber(obj) {
  return nativeToString.call(obj) === '[object Number]';
}

/**
 * @param {any} obj
 *
 * @return {boolean}
 */
function isFunction(obj) {
  const tag = nativeToString.call(obj);

  return (
    tag === '[object Function]' ||
    tag === '[object AsyncFunction]' ||
    tag === '[object GeneratorFunction]' ||
    tag === '[object AsyncGeneratorFunction]' ||
    tag === '[object Proxy]'
  );
}

function isString(obj) {
  return nativeToString.call(obj) === '[object String]';
}


/**
 * Ensure collection is an array.
 *
 * @param {Object} obj
 */
function ensureArray(obj) {

  if (isArray(obj)) {
    return;
  }

  throw new Error('must supply array');
}

/**
 * Return true, if target owns a property with the given key.
 *
 * @param {Object} target
 * @param {String} key
 *
 * @return {Boolean}
 */
function has(target, key) {
  return !isNil(target) && nativeHasOwnProperty.call(target, key);
}

/**
 * @template T
 * @typedef { (
 *   ((e: T) => boolean) |
 *   ((e: T, idx: number) => boolean) |
 *   ((e: T, key: string) => boolean) |
 *   string |
 *   number
 * ) } Matcher
 */

/**
 * @template T
 * @template U
 *
 * @typedef { (
 *   ((e: T) => U) | string | number
 * ) } Extractor
 */


/**
 * @template T
 * @typedef { (val: T, key: any) => boolean } MatchFn
 */

/**
 * @template T
 * @typedef { T[] } ArrayCollection
 */

/**
 * @template T
 * @typedef { { [key: string]: T } } StringKeyValueCollection
 */

/**
 * @template T
 * @typedef { { [key: number]: T } } NumberKeyValueCollection
 */

/**
 * @template T
 * @typedef { StringKeyValueCollection<T> | NumberKeyValueCollection<T> } KeyValueCollection
 */

/**
 * @template T
 * @typedef { KeyValueCollection<T> | ArrayCollection<T> } Collection
 */

/**
 * Find element in collection.
 *
 * @template T
 * @param {Collection<T>} collection
 * @param {Matcher<T>} matcher
 *
 * @return {Object}
 */
function find(collection, matcher) {

  const matchFn = toMatcher(matcher);

  let match;

  forEach(collection, function(val, key) {
    if (matchFn(val, key)) {
      match = val;

      return false;
    }
  });

  return match;

}


/**
 * Find element index in collection.
 *
 * @template T
 * @param {Collection<T>} collection
 * @param {Matcher<T>} matcher
 *
 * @return {number | string | undefined}
 */
function findIndex(collection, matcher) {

  const matchFn = toMatcher(matcher);

  let idx = isArray(collection) ? -1 : undefined;

  forEach(collection, function(val, key) {
    if (matchFn(val, key)) {
      idx = key;

      return false;
    }
  });

  return idx;
}


/**
 * Filter elements in collection.
 *
 * @template T
 * @param {Collection<T>} collection
 * @param {Matcher<T>} matcher
 *
 * @return {T[]} result
 */
function filter(collection, matcher) {

  const matchFn = toMatcher(matcher);

  let result = [];

  forEach(collection, function(val, key) {
    if (matchFn(val, key)) {
      result.push(val);
    }
  });

  return result;
}


/**
 * Iterate over collection; returning something
 * (non-undefined) will stop iteration.
 *
 * @template T
 * @param {Collection<T>} collection
 * @param { ((item: T, idx: number) => (boolean|void)) | ((item: T, key: string) => (boolean|void)) } iterator
 *
 * @return {T} return result that stopped the iteration
 */
function forEach(collection, iterator) {

  let val,
      result;

  if (isUndefined(collection)) {
    return;
  }

  const convertKey = isArray(collection) ? toNum : identity;

  for (let key in collection) {

    if (has(collection, key)) {
      val = collection[key];

      result = iterator(val, convertKey(key));

      if (result === false) {
        return val;
      }
    }
  }
}

/**
 * Return collection without element.
 *
 * @template T
 * @param {ArrayCollection<T>} arr
 * @param {Matcher<T>} matcher
 *
 * @return {T[]}
 */
function without(arr, matcher) {

  if (isUndefined(arr)) {
    return [];
  }

  ensureArray(arr);

  const matchFn = toMatcher(matcher);

  return arr.filter(function(el, idx) {
    return !matchFn(el, idx);
  });

}


/**
 * Reduce collection, returning a single result.
 *
 * @template T
 * @template V
 *
 * @param {Collection<T>} collection
 * @param {(result: V, entry: T, index: any) => V} iterator
 * @param {V} result
 *
 * @return {V} result returned from last iterator
 */
function reduce(collection, iterator, result) {

  forEach(collection, function(value, idx) {
    result = iterator(result, value, idx);
  });

  return result;
}


/**
 * Return true if every element in the collection
 * matches the criteria.
 *
 * @param  {Object|Array} collection
 * @param  {Function} matcher
 *
 * @return {Boolean}
 */
function every(collection, matcher) {

  return !!reduce(collection, function(matches, val, key) {
    return matches && matcher(val, key);
  }, true);
}


/**
 * Return true if some elements in the collection
 * match the criteria.
 *
 * @param  {Object|Array} collection
 * @param  {Function} matcher
 *
 * @return {Boolean}
 */
function some(collection, matcher) {

  return !!find(collection, matcher);
}


/**
 * Transform a collection into another collection
 * by piping each member through the given fn.
 *
 * @param  {Object|Array}   collection
 * @param  {Function} fn
 *
 * @return {Array} transformed collection
 */
function map(collection, fn) {

  let result = [];

  forEach(collection, function(val, key) {
    result.push(fn(val, key));
  });

  return result;
}


/**
 * Get the collections keys.
 *
 * @param  {Object|Array} collection
 *
 * @return {Array}
 */
function keys(collection) {
  return collection && Object.keys(collection) || [];
}


/**
 * Shorthand for `keys(o).length`.
 *
 * @param  {Object|Array} collection
 *
 * @return {Number}
 */
function size(collection) {
  return keys(collection).length;
}


/**
 * Get the values in the collection.
 *
 * @param  {Object|Array} collection
 *
 * @return {Array}
 */
function values(collection) {
  return map(collection, (val) => val);
}


/**
 * Group collection members by attribute.
 *
 * @param {Object|Array} collection
 * @param {Extractor} extractor
 *
 * @return {Object} map with { attrValue => [ a, b, c ] }
 */
function groupBy(collection, extractor, grouped = {}) {

  extractor = toExtractor(extractor);

  forEach(collection, function(val) {
    let discriminator = extractor(val) || '_';

    let group = grouped[discriminator];

    if (!group) {
      group = grouped[discriminator] = [];
    }

    group.push(val);
  });

  return grouped;
}


function uniqueBy(extractor, ...collections) {

  extractor = toExtractor(extractor);

  let grouped = {};

  forEach(collections, (c) => groupBy(c, extractor, grouped));

  let result = map(grouped, function(val, key) {
    return val[0];
  });

  return result;
}


const unionBy = uniqueBy;



/**
 * Sort collection by criteria.
 *
 * @template T
 *
 * @param {Collection<T>} collection
 * @param {Extractor<T, number | string>} extractor
 *
 * @return {Array}
 */
function sortBy(collection, extractor) {

  extractor = toExtractor(extractor);

  let sorted = [];

  forEach(collection, function(value, key) {
    let disc = extractor(value, key);

    let entry = {
      d: disc,
      v: value
    };

    for (var idx = 0; idx < sorted.length; idx++) {
      let { d } = sorted[idx];

      if (disc < d) {
        sorted.splice(idx, 0, entry);
        return;
      }
    }

    // not inserted, append (!)
    sorted.push(entry);
  });

  return map(sorted, (e) => e.v);
}


/**
 * Create an object pattern matcher.
 *
 * @example
 *
 * ```javascript
 * const matcher = matchPattern({ id: 1 });
 *
 * let element = find(elements, matcher);
 * ```
 *
 * @template T
 *
 * @param {T} pattern
 *
 * @return { (el: any) =>  boolean } matcherFn
 */
function matchPattern(pattern) {

  return function(el) {

    return every(pattern, function(val, key) {
      return el[key] === val;
    });

  };
}


/**
 * @param {string | ((e: any) => any) } extractor
 *
 * @return { (e: any) => any }
 */
function toExtractor(extractor) {

  /**
   * @satisfies { (e: any) => any }
   */
  return isFunction(extractor) ? extractor : (e) => {

    // @ts-ignore: just works
    return e[extractor];
  };
}


/**
 * @template T
 * @param {Matcher<T>} matcher
 *
 * @return {MatchFn<T>}
 */
function toMatcher(matcher) {
  return isFunction(matcher) ? matcher : (e) => {
    return e === matcher;
  };
}


function identity(arg) {
  return arg;
}

function toNum(arg) {
  return Number(arg);
}

/**
 * @template {(...args: any[]) => any} T
 * @typedef { {
 *   (...args: Parameters<T>): void;
 *   flush: () => void;
 *   cancel: () => void;
 * } } DebouncedFunction
 */

/**
 * Debounce fn, calling it only once if the given time
 * elapsed between calls.
 *
 * Lodash-style the function exposes methods to `#clear`
 * and `#flush` to control internal behavior.
 *
 * @template {(...args: any[]) => any} T
 *
 * @param  {T} fn
 * @param  {number} timeout
 *
 * @return {DebouncedFunction<T>} debounced function
 */
function debounce(fn, timeout) {

  let timer;

  let lastArgs;
  let lastThis;

  let lastNow;

  function fire(force) {

    let now = Date.now();

    let scheduledDiff = force ? 0 : (lastNow + timeout) - now;

    if (scheduledDiff > 0) {
      return schedule(scheduledDiff);
    }

    fn.apply(lastThis, lastArgs);

    clear();
  }

  function schedule(timeout) {
    timer = setTimeout(fire, timeout);
  }

  function clear() {
    if (timer) {
      clearTimeout(timer);
    }

    timer = lastNow = lastArgs = lastThis = undefined;
  }

  function flush() {
    if (timer) {
      fire(true);
    }

    clear();
  }

  /**
   * @type {DebouncedFunction<T>}
   */
  function callback(...args) {
    lastNow = Date.now();

    lastArgs = args;
    lastThis = this;

    // ensure an execution is scheduled
    if (!timer) {
      schedule(timeout);
    }
  }

  callback.flush = flush;
  callback.cancel = clear;

  return callback;
}

/**
 * Throttle fn, calling at most once
 * in the given interval.
 *
 * @template {(...args: any[]) => any} T
 *
 * @param  {T} fn
 * @param  {number} interval
 *
 * @return {(...args: Parameters<T>) => void} throttled function
 */
function throttle(fn, interval) {
  let throttling = false;

  return function(...args) {

    if (throttling) {
      return;
    }

    fn(...args);
    throttling = true;

    setTimeout(() => {
      throttling = false;
    }, interval);
  };
}

/**
 * Bind function against target <this>.
 *
 * @param  {Function} fn
 * @param  {Object}   target
 *
 * @return {Function} bound function
 */
function bind(fn, target) {
  return fn.bind(target);
}

/**
 * Convenience wrapper for `Object.assign`.
 *
 * @param {Object} target
 * @param {...Object} others
 *
 * @return {Object} the target
 */
function assign(target, ...others) {
  return Object.assign(target, ...others);
}

/**
 * Sets a nested property of a given object to the specified value.
 *
 * This mutates the object and returns it.
 *
 * @template T
 *
 * @param {T} target The target of the set operation.
 * @param {(string|number)[]} path The path to the nested value.
 * @param {any} value The value to set.
 *
 * @return {T}
 */
function set(target, path, value) {

  let currentTarget = target;

  forEach(path, function(key, idx) {

    if (typeof key !== 'number' && typeof key !== 'string') {
      throw new Error('illegal key type: ' + typeof key + '. Key should be of type number or string.');
    }

    if (key === 'constructor') {
      throw new Error('illegal key: constructor');
    }

    if (key === '__proto__') {
      throw new Error('illegal key: __proto__');
    }

    let nextKey = path[idx + 1];
    let nextTarget = currentTarget[key];

    if (isDefined(nextKey) && isNil(nextTarget)) {
      nextTarget = currentTarget[key] = isNaN(+nextKey) ? {} : [];
    }

    if (isUndefined(nextKey)) {
      if (isUndefined(value)) {
        delete currentTarget[key];
      } else {
        currentTarget[key] = value;
      }
    } else {
      currentTarget = nextTarget;
    }
  });

  return target;
}


/**
 * Gets a nested property of a given object.
 *
 * @param {Object} target The target of the get operation.
 * @param {(string|number)[]} path The path to the nested value.
 * @param {any} [defaultValue] The value to return if no value exists.
 *
 * @return {any}
 */
function get(target, path, defaultValue) {

  let currentTarget = target;

  forEach(path, function(key) {

    // accessing nil property yields <undefined>
    if (isNil(currentTarget)) {
      currentTarget = undefined;

      return false;
    }

    currentTarget = currentTarget[key];
  });

  return isUndefined(currentTarget) ? defaultValue : currentTarget;
}

/**
 * Pick properties from the given target.
 *
 * @template T
 * @template {any[]} V
 *
 * @param {T} target
 * @param {V} properties
 *
 * @return Pick<T, V>
 */
function pick(target, properties) {

  let result = {};

  let obj = Object(target);

  forEach(properties, function(prop) {

    if (prop in obj) {
      result[prop] = target[prop];
    }
  });

  return result;
}

/**
 * Pick all target properties, excluding the given ones.
 *
 * @template T
 * @template {any[]} V
 *
 * @param {T} target
 * @param {V} properties
 *
 * @return {Omit<T, V>} target
 */
function omit(target, properties) {

  let result = {};

  let obj = Object(target);

  forEach(obj, function(prop, key) {

    if (properties.indexOf(key) === -1) {
      result[key] = prop;
    }
  });

  return result;
}

/**
 * Recursively merge `...sources` into given target.
 *
 * Does support merging objects; does not support merging arrays.
 *
 * @param {Object} target
 * @param {...Object} sources
 *
 * @return {Object} the target
 */
function merge(target, ...sources) {

  if (!sources.length) {
    return target;
  }

  forEach(sources, function(source) {

    // skip non-obj sources, i.e. null
    if (!source || !isObject(source)) {
      return;
    }

    forEach(source, function(sourceVal, key) {

      if (key === '__proto__') {
        return;
      }

      let targetVal = target[key];

      if (isObject(sourceVal)) {

        if (!isObject(targetVal)) {

          // override target[key] with object
          targetVal = {};
        }

        target[key] = merge(targetVal, sourceVal);
      } else {
        target[key] = sourceVal;
      }

    });
  });

  return target;
}

var dist = /*#__PURE__*/Object.freeze({
	__proto__: null,
	assign: assign,
	bind: bind,
	debounce: debounce,
	ensureArray: ensureArray,
	every: every,
	filter: filter,
	find: find,
	findIndex: findIndex,
	flatten: flatten,
	forEach: forEach,
	get: get,
	groupBy: groupBy,
	has: has,
	isArray: isArray,
	isDefined: isDefined,
	isFunction: isFunction,
	isNil: isNil,
	isNumber: isNumber,
	isObject: isObject,
	isString: isString,
	isUndefined: isUndefined,
	keys: keys,
	map: map,
	matchPattern: matchPattern,
	merge: merge,
	omit: omit,
	pick: pick,
	reduce: reduce,
	set: set,
	size: size,
	some: some,
	sortBy: sortBy,
	throttle: throttle,
	unionBy: unionBy,
	uniqueBy: uniqueBy,
	values: values,
	without: without
});

var require$$1 = /*@__PURE__*/getAugmentedNamespace(dist);

var linkEvent;
var hasRequiredLinkEvent;

function requireLinkEvent () {
	if (hasRequiredLinkEvent) return linkEvent;
	hasRequiredLinkEvent = 1;
	const {
	  groupBy
	} = require$$1;

	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that verifies that link events are properly used.
	 *
	 * This implies:
	 *
	 *   * for every link throw there exists a link catch within
	 *     the same scope, and vice versa
	 *   * there exists only a single pair of [ throw, catch ] links
	 *     with a given name, per scope
	 *   * link events have a name
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	linkEvent = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:FlowElementsContainer')) {
	      return;
	    }

	    const links = (node.flowElements || []).filter(isLinkEvent);

	    for (const link of links) {
	      if (!getLinkName(link)) {
	        reporter.report(link.id, 'Link event is missing link name');
	      }
	    }

	    const names = groupBy(links, link => getLinkName(link));

	    for (const [ name, events ] of Object.entries(names)) {

	      // ignore unnamed (validated earlier)
	      if (!name) {
	        continue;
	      }

	      // missing catch or throw event
	      if (events.length === 1) {
	        const event = events[0];

	        reporter.report(event.id, `Link ${isThrowEvent(event) ? 'catch' : 'throw' } event with link name <${ name }> missing in scope`);
	        continue;
	      }

	      const catchEvents = events.filter(isCatchEvent);
	      if (catchEvents.length > 1) {
	        for (const event of catchEvents) {
	          reporter.report(event.id, `Duplicate link catch event with link name <${name}> in scope`);
	        }
	      } else if (catchEvents.length === 0) {

	        // all events in scope are throw events
	        for (const event of events) {
	          reporter.report(event.id, `Link catch event with link name <${ name }> missing in scope`);
	        }
	      }
	    }

	  }

	  return annotateRule('link-event', {
	    check
	  });
	};


	// helpers /////////////////

	function isLinkEvent(node) {

	  var eventDefinitions = node.eventDefinitions || [];

	  if (!is(node, 'bpmn:Event')) {
	    return false;
	  }

	  return eventDefinitions.some(
	    definition => is(definition, 'bpmn:LinkEventDefinition')
	  );
	}

	function getLinkName(linkEvent) {
	  return linkEvent.get('eventDefinitions').find(def => is(def, 'bpmn:LinkEventDefinition')).name;
	}

	function isThrowEvent(node) {
	  return is(node, 'bpmn:ThrowEvent');
	}

	function isCatchEvent(node) {
	  return is(node, 'bpmn:CatchEvent');
	}
	return linkEvent;
}

var linkEventExports = requireLinkEvent();
var rule_8 = /*@__PURE__*/getDefaultExportFromCjs(linkEventExports);

var noBpmndi;
var hasRequiredNoBpmndi;

function requireNoBpmndi () {
	if (hasRequiredNoBpmndi) return noBpmndi;
	hasRequiredNoBpmndi = 1;
	const {
	  is
	} = require$$0;

	const {
	  flatten
	} = require$$1;

	const {
	  annotateRule
	} = requireHelper();

	/**
	 * @typedef { import('../lib/types.js').ModdleElement } ModdleElement
	 */


	/**
	 * A rule that checks that there is no BPMNDI information missing for elements,
	 * which require BPMNDI.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	noBpmndi = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:Definitions')) {
	      return false;
	    }

	    // (1) Construct array of all BPMN elements
	    const bpmnElements = getAllBpmnElements(node.rootElements);

	    // (2) Filter BPMN elements without visual representation
	    const visualBpmnElements = bpmnElements.filter(hasVisualRepresentation);

	    // (3) Construct array of BPMNDI references
	    const diBpmnReferences = getAllDiBpmnReferences(node);

	    // (4) Report elements without BPMNDI
	    visualBpmnElements.forEach((element) => {
	      if (diBpmnReferences.indexOf(element.id) === -1) {
	        reporter.report(element.id, 'Element is missing bpmndi');
	      }
	    });
	  }

	  return annotateRule('no-bpmndi', {
	    check
	  });

	};


	// helpers /////////////////////////////

	/**
	 * Get all BPMN elements within a bpmn:Definitions node
	 *
	 * @param { ModdleElement[] } rootElements - An array of Moddle rootElements
	 *
	 * @return { { id: string, $type: string }[] } A flat array with all BPMN elements, each represented with { id: elementId, $type: elementType }
	 */
	function getAllBpmnElements(rootElements) {

	  return flatten(rootElements.map((rootElement) => {
	    const laneSet =
	      rootElement.laneSets && rootElement.laneSets[0] || rootElement.childLaneSet;

	    // Include
	    // * flowElements (e.g., tasks, sequenceFlows),
	    // * nested flowElements,
	    // * participants,
	    // * artifacts (groups),
	    // * laneSets
	    // * nested laneSets
	    // * childLaneSets
	    // * nested childLaneSets
	    // * messageFlows
	    const elements = flatten([
	      rootElement.flowElements || [],
	      (rootElement.flowElements && getAllBpmnElements(rootElement.flowElements.filter(hasFlowElements))) || [],
	      rootElement.participants || [],
	      rootElement.artifacts || [],
	      laneSet && laneSet.lanes || [],
	      laneSet && laneSet.lanes && getAllBpmnElements(laneSet.lanes.filter(hasChildLaneSet)) || [],
	      rootElement.messageFlows || []
	    ]);

	    if (elements.length > 0) {
	      return elements.map((element) => {

	        return {
	          id: element.id,
	          $type: element.$type
	        };
	      });
	    } else {

	      // We are not interested in the rest here (DI)
	      return [];
	    }
	  }));
	}

	/**
	 * Get all BPMN elements within a bpmn:Definitions node
	 *
	 * @param {ModdleElement} definitionsNode - A moddleElement representing the
	 *   bpmn:Definitions element
	 *
	 * @return {string[]} ids of all BPMNDI element part of
	 *   this bpmn:Definitions node
	 */
	function getAllDiBpmnReferences(definitionsNode) {
	  return flatten(
	    definitionsNode.get('diagrams').map((diagram) => {

	      const diElements = diagram.plane.planeElement || [];

	      return diElements.map((element) => {

	        return element.bpmnElement?.id;
	      });
	    })
	  );
	}

	/**
	 * @param { ModdleElement } element
	 *
	 * @return {boolean}
	 */
	function hasVisualRepresentation(element) {
	  const noVisRepresentation = [ 'bpmn:DataObject' ];

	  return noVisRepresentation.includes(element.$type) ? false : true;
	}

	/**
	 * @param { ModdleElement } element
	 *
	 * @return {boolean}
	 */
	function hasFlowElements(element) {
	  return element.flowElements ? true : false;
	}

	/**
	 * @param { ModdleElement } element
	 *
	 * @return {boolean}
	 */
	function hasChildLaneSet(element) {
	  return element.childLaneSet ? true : false;
	}
	return noBpmndi;
}

var noBpmndiExports = requireNoBpmndi();
var rule_9 = /*@__PURE__*/getDefaultExportFromCjs(noBpmndiExports);

var noComplexGateway;
var hasRequiredNoComplexGateway;

function requireNoComplexGateway () {
	if (hasRequiredNoComplexGateway) return noComplexGateway;
	hasRequiredNoComplexGateway = 1;
	const checkDiscouragedNodeType = requireHelper().checkDiscouragedNodeType;

	noComplexGateway = checkDiscouragedNodeType('bpmn:ComplexGateway', 'no-complex-gateway');
	return noComplexGateway;
}

var noComplexGatewayExports = requireNoComplexGateway();
var rule_10 = /*@__PURE__*/getDefaultExportFromCjs(noComplexGatewayExports);

var noDisconnected;
var hasRequiredNoDisconnected;

function requireNoDisconnected () {
	if (hasRequiredNoDisconnected) return noDisconnected;
	hasRequiredNoDisconnected = 1;
	const {
	  isAny,
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that verifies that there exists no disconnected
	 * flow elements, i.e. elements without incoming or outgoing sequence flows.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	noDisconnected = function() {

	  function check(node, reporter) {

	    if (!isAny(node, [
	      'bpmn:Task',
	      'bpmn:Gateway',
	      'bpmn:SubProcess',
	      'bpmn:Event'
	    ]) || node.triggeredByEvent) {
	      return;
	    }

	    // compensation activity and boundary events are
	    // linked visually via associations. If these associations
	    // exist we are fine, too
	    if (isCompensationLinked(node)) {
	      return;
	    }

	    // adhoc subprocesses can have disconnected activities
	    if (is(node.$parent, 'bpmn:AdHocSubProcess')) {
	      return;
	    }

	    const incoming = node.incoming || [];
	    const outgoing = node.outgoing || [];

	    if (!incoming.length && !outgoing.length) {
	      reporter.report(node.id, 'Element is not connected');
	    }
	  }

	  return annotateRule('no-disconnected', {
	    check
	  });
	};


	// helpers /////////////////

	function isCompensationBoundary(node) {

	  var eventDefinitions = node.eventDefinitions;

	  if (!is(node, 'bpmn:BoundaryEvent')) {
	    return false;
	  }

	  if (!eventDefinitions || eventDefinitions.length !== 1) {
	    return false;
	  }

	  return is(eventDefinitions[0], 'bpmn:CompensateEventDefinition');
	}

	function isCompensationActivity(node) {
	  return node.isForCompensation;
	}

	function isCompensationLinked(node) {
	  var source = isCompensationBoundary(node);
	  var target = isCompensationActivity(node);

	  // TODO(nikku): check, whether compensation association exists
	  return source || target;
	}
	return noDisconnected;
}

var noDisconnectedExports = requireNoDisconnected();
var rule_11 = /*@__PURE__*/getDefaultExportFromCjs(noDisconnectedExports);

var noDuplicateSequenceFlows;
var hasRequiredNoDuplicateSequenceFlows;

function requireNoDuplicateSequenceFlows () {
	if (hasRequiredNoDuplicateSequenceFlows) return noDuplicateSequenceFlows;
	hasRequiredNoDuplicateSequenceFlows = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that verifies that there are no disconnected
	 * flow elements, i.e. elements without incoming or outgoing sequence flows.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	noDuplicateSequenceFlows = function() {

	  const keyed = {};

	  const outgoingReported = {};
	  const incomingReported = {};

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:SequenceFlow')) {
	      return;
	    }

	    const key = flowKey(node);

	    if (key in keyed) {
	      reporter.report(node.id, 'SequenceFlow is a duplicate');

	      const sourceId = node.sourceRef.id;
	      const targetId = node.targetRef.id;

	      if (!outgoingReported[sourceId]) {
	        reporter.report(sourceId, 'Duplicate outgoing sequence flows');

	        outgoingReported[sourceId] = true;
	      }

	      if (!incomingReported[targetId]) {
	        reporter.report(targetId, 'Duplicate incoming sequence flows');

	        incomingReported[targetId] = true;
	      }
	    } else {
	      keyed[key] = node;
	    }
	  }

	  return annotateRule('no-duplicate-sequence-flows', {
	    check
	  });

	};


	// helpers /////////////////

	function flowKey(flow) {
	  const conditionExpression = flow.conditionExpression;

	  const condition = conditionExpression ? conditionExpression.body : '';
	  const source = flow.sourceRef ? flow.sourceRef.id : flow.id;
	  const target = flow.targetRef ? flow.targetRef.id : flow.id;

	  return source + '#' + target + '#' + condition;
	}
	return noDuplicateSequenceFlows;
}

var noDuplicateSequenceFlowsExports = requireNoDuplicateSequenceFlows();
var rule_12 = /*@__PURE__*/getDefaultExportFromCjs(noDuplicateSequenceFlowsExports);

var noGatewayJoinFork;
var hasRequiredNoGatewayJoinFork;

function requireNoGatewayJoinFork () {
	if (hasRequiredNoGatewayJoinFork) return noGatewayJoinFork;
	hasRequiredNoGatewayJoinFork = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks, whether a gateway forks and joins
	 * at the same time.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	noGatewayJoinFork = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:Gateway')) {
	      return;
	    }

	    const incoming = node.incoming || [];
	    const outgoing = node.outgoing || [];

	    if (incoming.length > 1 && outgoing.length > 1) {
	      reporter.report(node.id, 'Gateway forks and joins');
	    }
	  }

	  return annotateRule('no-gateway-join-fork', {
	    check
	  });

	};
	return noGatewayJoinFork;
}

var noGatewayJoinForkExports = requireNoGatewayJoinFork();
var rule_13 = /*@__PURE__*/getDefaultExportFromCjs(noGatewayJoinForkExports);

var noImplicitSplit;
var hasRequiredNoImplicitSplit;

function requireNoImplicitSplit () {
	if (hasRequiredNoImplicitSplit) return noImplicitSplit;
	hasRequiredNoImplicitSplit = 1;
	const {
	  isAny
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks that no implicit split is modeled
	 * starting from a task.
	 *
	 * Users should model the parallel splitting gateway
	 * explicitly instead.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	noImplicitSplit = function() {

	  function check(node, reporter) {

	    if (!isAny(node, [
	      'bpmn:Activity',
	      'bpmn:Event'
	    ])) {
	      return;
	    }

	    const outgoing = node.outgoing || [];

	    const outgoingWithoutCondition = outgoing.filter((flow) => {
	      return !hasCondition(flow) && !isDefaultFlow(node, flow);
	    });

	    if (outgoingWithoutCondition.length > 1) {
	      reporter.report(node.id, 'Flow splits implicitly');
	    }
	  }

	  return annotateRule('no-implicit-split', {
	    check
	  });

	};


	// helpers /////////////////////////////

	function hasCondition(flow) {
	  return !!flow.conditionExpression;
	}

	function isDefaultFlow(node, flow) {
	  return node['default'] === flow;
	}
	return noImplicitSplit;
}

var noImplicitSplitExports = requireNoImplicitSplit();
var rule_14 = /*@__PURE__*/getDefaultExportFromCjs(noImplicitSplitExports);

var noImplicitEnd;
var hasRequiredNoImplicitEnd;

function requireNoImplicitEnd () {
	if (hasRequiredNoImplicitEnd) return noImplicitEnd;
	hasRequiredNoImplicitEnd = 1;
	const {
	  is,
	  isAny
	} = require$$0;

	const {
	  findParent,
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks that an element is not an implicit end (token sink).
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	noImplicitEnd = function() {

	  function isLinkEvent(node) {
	    const eventDefinitions = node.eventDefinitions || [];

	    return eventDefinitions.length && eventDefinitions.every(
	      definition => is(definition, 'bpmn:LinkEventDefinition')
	    );
	  }

	  function isCompensationEvent(node) {
	    const eventDefinitions = node.eventDefinitions || [];

	    return eventDefinitions.length && eventDefinitions.every(
	      definition => is(definition, 'bpmn:CompensateEventDefinition')
	    );
	  }

	  function hasCompensationActivity(node) {
	    const parent = findParent(node, 'bpmn:Process');

	    const artifacts = parent.artifacts || [];

	    return artifacts.some((element) => {
	      if (!is(element, 'bpmn:Association')) {
	        return false;
	      }

	      const source = element.sourceRef;

	      return source.id === node.id;
	    });
	  }

	  function isForCompensation(node) {
	    return node.isForCompensation;
	  }

	  function isImplicitEnd(node) {
	    const outgoing = node.outgoing || [];

	    if (is(node, 'bpmn:SubProcess') && node.triggeredByEvent) {
	      return false;
	    }

	    if (is(node, 'bpmn:IntermediateThrowEvent') && isLinkEvent(node)) {
	      return false;
	    }

	    if (is(node.$parent, 'bpmn:AdHocSubProcess')) {
	      return false;
	    }

	    if (is(node, 'bpmn:EndEvent')) {
	      return false;
	    }

	    if (is(node, 'bpmn:BoundaryEvent') && isCompensationEvent(node) && hasCompensationActivity(node)) {
	      return false;
	    }

	    if (is(node, 'bpmn:Activity') && isForCompensation(node)) {
	      return false;
	    }

	    return outgoing.length === 0;
	  }

	  function check(node, reporter) {

	    if (!isAny(node, [ 'bpmn:Event', 'bpmn:Activity', 'bpmn:Gateway' ])) {
	      return;
	    }

	    if (isImplicitEnd(node)) {
	      reporter.report(node.id, 'Element is an implicit end');
	    }
	  }

	  return annotateRule('no-implicit-end', {
	    check
	  });
	};
	return noImplicitEnd;
}

var noImplicitEndExports = requireNoImplicitEnd();
var rule_15 = /*@__PURE__*/getDefaultExportFromCjs(noImplicitEndExports);

var noImplicitStart;
var hasRequiredNoImplicitStart;

function requireNoImplicitStart () {
	if (hasRequiredNoImplicitStart) return noImplicitStart;
	hasRequiredNoImplicitStart = 1;
	const {
	  is,
	  isAny
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();

	/**
	 * A rule that checks that an element is not an implicit start (token spawn).
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	noImplicitStart = function() {

	  function isLinkEvent(node) {
	    const eventDefinitions = node.eventDefinitions || [];

	    return eventDefinitions.length && eventDefinitions.every(
	      definition => is(definition, 'bpmn:LinkEventDefinition')
	    );
	  }

	  function isForCompensation(node) {
	    return node.isForCompensation;
	  }

	  function isImplicitStart(node) {
	    const incoming = node.incoming || [];

	    if (is(node, 'bpmn:Activity') && isForCompensation(node)) {
	      return false;
	    }

	    if (is(node.$parent, 'bpmn:AdHocSubProcess')) {
	      return false;
	    }

	    if (is(node, 'bpmn:SubProcess') && node.triggeredByEvent) {
	      return false;
	    }

	    if (is(node, 'bpmn:IntermediateCatchEvent') && isLinkEvent(node)) {
	      return false;
	    }

	    if (isAny(node, [ 'bpmn:StartEvent', 'bpmn:BoundaryEvent' ])) {
	      return false;
	    }

	    return incoming.length === 0;
	  }

	  function check(node, reporter) {

	    if (!isAny(node, [ 'bpmn:Event', 'bpmn:Activity', 'bpmn:Gateway' ])) {
	      return;
	    }

	    if (isImplicitStart(node)) {
	      reporter.report(node.id, 'Element is an implicit start');
	    }
	  }

	  return annotateRule('no-implicit-start', {
	    check
	  });
	};
	return noImplicitStart;
}

var noImplicitStartExports = requireNoImplicitStart();
var rule_16 = /*@__PURE__*/getDefaultExportFromCjs(noImplicitStartExports);

var noInclusiveGateway;
var hasRequiredNoInclusiveGateway;

function requireNoInclusiveGateway () {
	if (hasRequiredNoInclusiveGateway) return noInclusiveGateway;
	hasRequiredNoInclusiveGateway = 1;
	const checkDiscouragedNodeType = requireHelper().checkDiscouragedNodeType;

	noInclusiveGateway = checkDiscouragedNodeType('bpmn:InclusiveGateway', 'no-inclusive-gateway');
	return noInclusiveGateway;
}

var noInclusiveGatewayExports = requireNoInclusiveGateway();
var rule_17 = /*@__PURE__*/getDefaultExportFromCjs(noInclusiveGatewayExports);

var noOverlappingElements;
var hasRequiredNoOverlappingElements;

function requireNoOverlappingElements () {
	if (hasRequiredNoOverlappingElements) return noOverlappingElements;
	hasRequiredNoOverlappingElements = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * Rule that checks if two elements overlap except:
	 *
	 * - Boundary events overlap their host
	 * - Child elements overlap / are on top of their parent (e.g., elements within a subProcess)
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	noOverlappingElements = function() {

	  function check(node, reporter) {
	    if (!is(node, 'bpmn:Definitions')) {
	      return;
	    }

	    const rootElements = node.rootElements || [];
	    const elementsToReport = new Set();
	    const elementsOutsideToReport = new Set();
	    const diObjects = getAllDiObjects(node);
	    const processElementsParentDiMap = new Map(); // map with sub/process as key and its parent boundary di object

	    rootElements
	      .filter(element => is(element, 'bpmn:Collaboration'))
	      .forEach(collaboration => {
	        const participants = collaboration.participants || [];
	        checkElementsArray(participants, elementsToReport, diObjects);

	        participants.forEach(participant => {
	          processElementsParentDiMap.set(participant.processRef, diObjects.get(participant));
	        });
	      });

	    rootElements
	      .filter(element => is(element, 'bpmn:Process'))
	      .forEach(process => {
	        const parentDi = processElementsParentDiMap.get(process) || {};
	        checkProcess(process, elementsToReport, elementsOutsideToReport, diObjects, parentDi);
	      });

	    // report elements
	    elementsToReport.forEach(element => reporter.report(element.id, 'Element overlaps with other element'));
	    elementsOutsideToReport.forEach(element => reporter.report(element.id, 'Element is outside of parent boundary'));
	  }

	  return annotateRule('no-overlapping-elements', {
	    check
	  });
	};

	// helpers /////////////////

	/**
	 * Recursively check subprocesses in a process
	 * @param {Object} node Process or SubProcess
	 * @param {Set} elementsToReport
	 * @param {Set} elementsOutsideToReport
	 * @param {Map} diObjects
	 */
	function checkProcess(node, elementsToReport, elementsOutsideToReport, diObjects, parentDi) {

	  const flowElements = node.flowElements || [];

	  const flowElementsWithDi = flowElements.filter(element => diObjects.has(element));

	  // check child elements for overlap
	  checkElementsArray(flowElementsWithDi, elementsToReport, diObjects);

	  // check child elements outside parent boundary
	  //
	  //   * data objects do not have a visual representation
	  //   * for historical reasons data store references may be
	  //     outside of parent boundaries
	  //
	  flowElementsWithDi.forEach(element => {
	    if (
	      !is(element, 'bpmn:DataStoreReference') &&
	      isOutsideParentBoundary(diObjects.get(element).bounds, parentDi.bounds)
	    ) {
	      elementsOutsideToReport.add(element);
	    }
	  });

	  // recurse into subprocesses
	  const subProcesses = flowElements.filter(element => is(element, 'bpmn:SubProcess'));
	  subProcesses.forEach(subProcess => {
	    const subProcessDi = diObjects.get(subProcess) || {};
	    const subProcessParentBoundary = subProcessDi.isExpanded ? subProcessDi : {};
	    checkProcess(subProcess, elementsToReport, elementsOutsideToReport, diObjects, subProcessParentBoundary);
	  });
	}

	/**
	 * @param {Array} elements
	 * @param {Set} elementsToReport
	 */
	function checkElementsArray(elements, elementsToReport, diObjects) {
	  for (let i = 0; i < elements.length - 1; i++) {
	    const element = elements[i];

	    for (let j = i + 1; j < elements.length; j++) {
	      const element2 = elements[j];

	      // ignore if Boundary events overlap their host
	      // but still check if they overlap other elements
	      if (element.attachedToRef === element2 || element2.attachedToRef === element) {
	        continue;
	      }

	      const bounds1 = diObjects.get(element)?.bounds;
	      const bounds2 = diObjects.get(element2)?.bounds;

	      // ignore if an element doesn't have bounds
	      if (!bounds1 || !bounds2) {
	        continue;
	      }

	      if (isCollision(bounds1, bounds2)) {
	        elementsToReport.add(element);
	        elementsToReport.add(element2);
	      }
	    }
	  }
	}

	/**
	 * Check if child element is outside of parent boundary
	 */
	function isOutsideParentBoundary(childBounds, parentBounds) {
	  if (!isValidShapeElement(childBounds) || !isValidShapeElement(parentBounds)) {
	    return false;
	  }

	  const isTopLeftCornerInside = childBounds.x >= parentBounds.x && childBounds.y >= parentBounds.y;
	  const isBottomRightCornerInside = childBounds.x + childBounds.width <= parentBounds.x + parentBounds.width && childBounds.y + childBounds.height <= parentBounds.y + parentBounds.height;
	  const isInside = isTopLeftCornerInside && isBottomRightCornerInside;

	  return !isInside;
	}

	/**
	 * Check if two rectangle shapes collides
	 */
	function isCollision(firstBounds, secondBounds) {
	  if (!isValidShapeElement(firstBounds) || !isValidShapeElement(secondBounds)) {
	    return false;
	  }

	  const collisionX = firstBounds.x + firstBounds.width >= secondBounds.x && secondBounds.x + secondBounds.width >= firstBounds.x;
	  const collisionY = firstBounds.y + firstBounds.height >= secondBounds.y && secondBounds.y + secondBounds.height >= firstBounds.y;

	  // collision on both axis
	  return collisionX && collisionY;
	}

	/**
	 * Checks if shape bounds has all necessary values for collision check
	 */
	function isValidShapeElement(bounds) {
	  return !!bounds && is(bounds, 'dc:Bounds') &&
	    typeof (bounds.x) === 'number' &&
	    typeof (bounds.y) === 'number' &&
	    typeof (bounds.width) === 'number' &&
	    typeof (bounds.height) === 'number';
	}

	/**
	 * Get all di object as one map object
	 * @param {Object} node bpmn:Definitions
	 * @returns {Map<Object, Object>} map of di objects with element as key
	 */
	function getAllDiObjects(node) {
	  const diObjects = new Map();
	  const diagrams = node.diagrams || [];

	  diagrams
	    .filter(diagram => !!diagram.plane)
	    .forEach(diagram => {
	      const planeElements = diagram.plane.planeElement || [];
	      planeElements
	        .filter(planeElement => !!planeElement.bpmnElement)
	        .forEach(planeElement => {
	          diObjects.set(planeElement.bpmnElement, planeElement);
	        });
	    });

	  return diObjects;
	}
	return noOverlappingElements;
}

var noOverlappingElementsExports = requireNoOverlappingElements();
var rule_18 = /*@__PURE__*/getDefaultExportFromCjs(noOverlappingElementsExports);

var singleBlankStartEvent;
var hasRequiredSingleBlankStartEvent;

function requireSingleBlankStartEvent () {
	if (hasRequiredSingleBlankStartEvent) return singleBlankStartEvent;
	hasRequiredSingleBlankStartEvent = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks whether not more than one blank start event
	 * exists per scope.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	singleBlankStartEvent = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:FlowElementsContainer')) {
	      return;
	    }

	    const flowElements = node.flowElements || [];

	    const blankStartEvents = flowElements.filter(function(flowElement) {

	      if (!is(flowElement, 'bpmn:StartEvent')) {
	        return false;
	      }

	      const eventDefinitions = flowElement.eventDefinitions || [];

	      return eventDefinitions.length === 0;
	    });

	    if (blankStartEvents.length > 1) {
	      const type = is(node, 'bpmn:SubProcess') ? 'Sub process' : 'Process';

	      reporter.report(node.id, type + ' has multiple blank start events');
	    }
	  }

	  return annotateRule('single-blank-start-event', {
	    check
	  });

	};
	return singleBlankStartEvent;
}

var singleBlankStartEventExports = requireSingleBlankStartEvent();
var rule_19 = /*@__PURE__*/getDefaultExportFromCjs(singleBlankStartEventExports);

var singleEventDefinition;
var hasRequiredSingleEventDefinition;

function requireSingleEventDefinition () {
	if (hasRequiredSingleEventDefinition) return singleEventDefinition;
	hasRequiredSingleEventDefinition = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that verifies that an event contains maximum one event definition.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	singleEventDefinition = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:Event')) {
	      return;
	    }

	    const eventDefinitions = node.eventDefinitions || [];

	    if (eventDefinitions.length > 1) {
	      reporter.report(node.id, 'Event has multiple event definitions', [ 'eventDefinitions' ]);
	    }
	  }

	  return annotateRule('single-event-definition', {
	    check
	  });

	};
	return singleEventDefinition;
}

var singleEventDefinitionExports = requireSingleEventDefinition();
var rule_20 = /*@__PURE__*/getDefaultExportFromCjs(singleEventDefinitionExports);

var startEventRequired;
var hasRequiredStartEventRequired;

function requireStartEventRequired () {
	if (hasRequiredStartEventRequired) return startEventRequired;
	hasRequiredStartEventRequired = 1;
	const {
	  is,
	  isAny
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks for the presence of a start event per scope.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	startEventRequired = function() {

	  function hasStartEvent(node) {
	    const flowElements = node.flowElements || [];

	    return (
	      flowElements.some(node => is(node, 'bpmn:StartEvent'))
	    );
	  }

	  function check(node, reporter) {

	    if (!isAny(node, [
	      'bpmn:Process',
	      'bpmn:SubProcess'
	    ]) || is(node, 'bpmn:AdHocSubProcess')) {
	      return;
	    }

	    if (!hasStartEvent(node)) {
	      const type = is(node, 'bpmn:SubProcess') ? 'Sub process' : 'Process';

	      reporter.report(node.id, type + ' is missing start event');
	    }
	  }

	  return annotateRule('start-event-required', {
	    check
	  });
	};
	return startEventRequired;
}

var startEventRequiredExports = requireStartEventRequired();
var rule_21 = /*@__PURE__*/getDefaultExportFromCjs(startEventRequiredExports);

var subProcessBlankStartEvent;
var hasRequiredSubProcessBlankStartEvent;

function requireSubProcessBlankStartEvent () {
	if (hasRequiredSubProcessBlankStartEvent) return subProcessBlankStartEvent;
	hasRequiredSubProcessBlankStartEvent = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks that start events inside a normal sub-processes
	 * are blank (do not have an event definition).
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	subProcessBlankStartEvent = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:SubProcess') || node.triggeredByEvent) {
	      return;
	    }

	    const flowElements = node.flowElements || [];

	    flowElements.forEach(function(flowElement) {

	      if (!is(flowElement, 'bpmn:StartEvent')) {
	        return false;
	      }

	      const eventDefinitions = flowElement.eventDefinitions || [];

	      if (eventDefinitions.length > 0) {
	        reporter.report(flowElement.id, 'Start event must be blank', [ 'eventDefinitions' ]);
	      }
	    });
	  }

	  return annotateRule('sub-process-blank-start-event', {
	    check
	  });

	};
	return subProcessBlankStartEvent;
}

var subProcessBlankStartEventExports = requireSubProcessBlankStartEvent();
var rule_22 = /*@__PURE__*/getDefaultExportFromCjs(subProcessBlankStartEventExports);

var superfluousGateway;
var hasRequiredSuperfluousGateway;

function requireSuperfluousGateway () {
	if (hasRequiredSuperfluousGateway) return superfluousGateway;
	hasRequiredSuperfluousGateway = 1;
	const {
	  is
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks, whether a gateway has only one source and target.
	 *
	 * Those gateways are superfluous since they don't do anything.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	superfluousGateway = function() {

	  function check(node, reporter) {

	    if (!is(node, 'bpmn:Gateway')) {
	      return;
	    }

	    const incoming = node.incoming || [];
	    const outgoing = node.outgoing || [];

	    if (incoming.length === 1 && outgoing.length === 1) {
	      reporter.report(node.id, 'Gateway is superfluous. It only has one source and target.');
	    }
	  }

	  return annotateRule('superfluous-gateway', {
	    check
	  });

	};
	return superfluousGateway;
}

var superfluousGatewayExports = requireSuperfluousGateway();
var rule_23 = /*@__PURE__*/getDefaultExportFromCjs(superfluousGatewayExports);

var superfluousTermination;
var hasRequiredSuperfluousTermination;

function requireSuperfluousTermination () {
	if (hasRequiredSuperfluousTermination) return superfluousTermination;
	hasRequiredSuperfluousTermination = 1;
	const {
	  is,
	  isAny
	} = require$$0;

	const {
	  annotateRule
	} = requireHelper();


	/**
	 * A rule that checks, whether a gateway has only one source and target.
	 *
	 * Those gateways are superfluous since they don't do anything.
	 *
	 * @type { import('../lib/types.js').RuleFactory }
	 */
	superfluousTermination = function() {

	  function check(node, reporter) {

	    if (!isAny(node, [ 'bpmn:Process', 'bpmn:SubProcess' ])) {
	      return;
	    }

	    const flowElements = node.flowElements || [];

	    const ends = flowElements.filter(
	      element => is(element, 'bpmn:FlowNode') && (element.outgoing || []).length === 0
	    );

	    const terminateEnds = ends.filter(isTerminateEnd);

	    if (terminateEnds.length !== 1) {

	      // TODO(nikku): only detect basic cases, do not
	      // do any kinds of elaborate flow analysis
	      return;
	    }

	    const superfluous = ends.every(
	      (end) => isInterruptingEventSub(end) || isTerminateEnd(end)
	    );

	    if (superfluous) {

	      for (const node of terminateEnds) {
	        reporter.report(node.id, 'Termination is superfluous.');
	      }
	    }
	  }

	  return annotateRule('superfluous-termination', {
	    check
	  });

	};

	function isTerminateEnd(element) {
	  return is(element, 'bpmn:EndEvent') && (element.eventDefinitions || []).some(
	    eventDefinition => is(eventDefinition, 'bpmn:TerminateEventDefinition')
	  );
	}

	function isInterruptingEventSub(element) {
	  const isEventSub = is(element, 'bpmn:SubProcess') && element.triggeredByEvent;

	  return isEventSub && (element.flowElements || []).some(
	    element => is(element, 'bpmn:StartEvent') && element.isInterrupting
	  );
	}
	return superfluousTermination;
}

var superfluousTerminationExports = requireSuperfluousTermination();
var rule_24 = /*@__PURE__*/getDefaultExportFromCjs(superfluousTerminationExports);

const cache = {};

/**
 * A resolver that caches rules and configuration as part of the bundle,
 * making them accessible in the browser.
 *
 * @param {Object} cache
 */
function Resolver() {}

Resolver.prototype.resolveRule = function(pkg, ruleName) {

  const rule = cache[pkg + '/' + ruleName];

  if (!rule) {
    throw new Error('cannot resolve rule <' + pkg + '/' + ruleName + '>: not bundled');
  }

  return rule;
};

Resolver.prototype.resolveConfig = function(pkg, configName) {
  throw new Error(
    'cannot resolve config <' + configName + '> in <' + pkg +'>: not bundled'
  );
};

const resolver = new Resolver();

const rules = {
  "ad-hoc-sub-process": "error",
  "conditional-flows": "error",
  "end-event-required": "error",
  "event-based-gateway": "error",
  "event-sub-process-typed-start-event": "error",
  "fake-join": "warn",
  "global": "warn",
  "label-required": "warn",
  "link-event": "error",
  "no-bpmndi": "error",
  "no-complex-gateway": "error",
  "no-disconnected": "error",
  "no-duplicate-sequence-flows": "error",
  "no-gateway-join-fork": "error",
  "no-implicit-split": "error",
  "no-implicit-end": "error",
  "no-implicit-start": "error",
  "no-inclusive-gateway": "warn",
  "no-overlapping-elements": "warn",
  "single-blank-start-event": "error",
  "single-event-definition": "error",
  "start-event-required": "error",
  "sub-process-blank-start-event": "error",
  "superfluous-gateway": "warn",
  "superfluous-termination": "warn"
};

const config = {
  rules: rules
};

const moddleExtensions = {};

const bundle = {
  resolver: resolver,
  config: config,
  moddleExtensions: moddleExtensions
};

cache['bpmnlint/ad-hoc-sub-process'] = rule_0;

cache['bpmnlint/conditional-flows'] = rule_1;

cache['bpmnlint/end-event-required'] = rule_2;

cache['bpmnlint/event-based-gateway'] = rule_3;

cache['bpmnlint/event-sub-process-typed-start-event'] = rule_4;

cache['bpmnlint/fake-join'] = rule_5;

cache['bpmnlint/global'] = rule_6;

cache['bpmnlint/label-required'] = rule_7;

cache['bpmnlint/link-event'] = rule_8;

cache['bpmnlint/no-bpmndi'] = rule_9;

cache['bpmnlint/no-complex-gateway'] = rule_10;

cache['bpmnlint/no-disconnected'] = rule_11;

cache['bpmnlint/no-duplicate-sequence-flows'] = rule_12;

cache['bpmnlint/no-gateway-join-fork'] = rule_13;

cache['bpmnlint/no-implicit-split'] = rule_14;

cache['bpmnlint/no-implicit-end'] = rule_15;

cache['bpmnlint/no-implicit-start'] = rule_16;

cache['bpmnlint/no-inclusive-gateway'] = rule_17;

cache['bpmnlint/no-overlapping-elements'] = rule_18;

cache['bpmnlint/single-blank-start-event'] = rule_19;

cache['bpmnlint/single-event-definition'] = rule_20;

cache['bpmnlint/start-event-required'] = rule_21;

cache['bpmnlint/sub-process-blank-start-event'] = rule_22;

cache['bpmnlint/superfluous-gateway'] = rule_23;

cache['bpmnlint/superfluous-termination'] = rule_24;

export { config, bundle as default, moddleExtensions, resolver };
