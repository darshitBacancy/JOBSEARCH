#!/usr/bin/env node
/**
 * Generates data/jobs.json — a FICTIONAL demo job dataset for the Job Search Assistant.
 *
 * Deterministic: a seeded PRNG (mulberry32) is used, so running this script again
 * produces byte-identical output. No dependencies.
 *
 *   node data/tools/generate-jobs.mjs
 */
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------- PRNG
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(20260925);
const int = (lo, hi) => lo + Math.floor(rand() * (hi - lo + 1));
const pick = (arr) => arr[Math.floor(rand() * arr.length)];
function sample(arr, n) {
  const copy = [...arr];
  const out = [];
  while (out.length < n && copy.length) out.push(copy.splice(Math.floor(rand() * copy.length), 1)[0]);
  return out;
}
const round50k = (v) => Math.round(v / 50000) * 50000;

// ---------------------------------------------------------------- reference data
export const ALLOWED_SKILLS = [
  'Java', 'Spring Boot', 'Spring Cloud', 'Hibernate', 'Microservices', 'Kafka', 'AWS', 'Azure', 'GCP',
  'Docker', 'Kubernetes', 'Terraform', 'Jenkins', 'GitHub Actions', 'PostgreSQL', 'MySQL', 'MongoDB',
  'Redis', 'Elasticsearch', 'REST APIs', 'GraphQL', 'JavaScript', 'TypeScript', 'React', 'Next.js',
  'Redux', 'Angular', 'Vue.js', 'HTML', 'CSS', 'Tailwind CSS', 'Node.js', 'Express', 'NestJS', 'Python',
  'Django', 'FastAPI', 'Flask', 'Pandas', 'NumPy', 'Machine Learning', 'Deep Learning', 'PyTorch',
  'TensorFlow', 'NLP', 'LLMs', 'Spark', 'Airflow', 'Snowflake', 'SQL', 'dbt', 'Power BI', 'Tableau', 'Go',
  'Rust', 'C#', '.NET', 'Kotlin', 'Swift', 'Android', 'iOS', 'Flutter', 'React Native', 'Selenium',
  'Cypress', 'Playwright', 'JUnit', 'Linux', 'Prometheus', 'Grafana', 'Figma', 'Scala', 'Salesforce',
  'SAP', 'Cybersecurity', 'Networking', 'Git', 'CI/CD', 'Agile',
];

const DOMAINS = {
  fintech: {
    products: [
      'builds a payment reconciliation platform used by mid-sized banks and NBFCs',
      'runs a UPI-first merchant payments app for small retailers',
      'provides a digital lending stack for co-operative banks',
    ],
    focus: ['payment reconciliation services', 'merchant settlement flows', 'loan origination workflows', 'real-time fraud scoring'],
  },
  healthtech: {
    products: [
      'operates a telemedicine scheduling platform connecting patients with clinics',
      'builds hospital information software for tier-2 city hospitals',
      'offers a diagnostics booking and lab-report app',
    ],
    focus: ['teleconsultation scheduling', 'electronic health record exchange', 'lab-report delivery pipelines', 'pharmacy inventory sync'],
  },
  ecommerce: {
    products: [
      'runs a fashion and lifestyle marketplace with thousands of independent sellers',
      'powers a quick-commerce grocery delivery experience in metro cities',
      'builds storefront and catalogue software for D2C brands',
    ],
    focus: ['catalogue and search services', 'checkout and order management', 'seller onboarding tools', 'personalised recommendations'],
  },
  logistics: {
    products: [
      'builds a route-optimisation and fleet-tracking platform for delivery companies',
      'operates a freight-booking marketplace for long-haul trucking',
      'provides warehouse management software for third-party logistics firms',
    ],
    focus: ['shipment tracking services', 'route planning engines', 'warehouse slotting and picking flows', 'driver dispatch systems'],
  },
  edtech: {
    products: [
      'runs a live-classes platform for competitive exam preparation',
      'builds a learning management system for universities',
      'offers an adaptive practice app for school students',
    ],
    focus: ['live-class streaming', 'assessment and grading engines', 'course authoring tools', 'learner progress analytics'],
  },
  saas: {
    products: [
      'builds a B2B workflow-automation suite for finance teams',
      'offers a customer-support helpdesk product used by SaaS companies',
      'develops an HR and payroll platform for growing businesses',
    ],
    focus: ['multi-tenant platform services', 'workflow automation engines', 'billing and subscription management', 'integration connectors'],
  },
  gaming: {
    products: [
      'publishes casual multiplayer mobile games played by millions of users',
      'builds a fantasy-sports and quiz gaming platform',
    ],
    focus: ['real-time matchmaking', 'leaderboard and rewards services', 'in-game economy systems', 'live-ops tooling'],
  },
  insurance: {
    products: [
      'offers a digital insurance distribution platform for motor and health policies',
      'builds claims-processing software for general insurers',
    ],
    focus: ['policy issuance services', 'claims adjudication workflows', 'premium calculation engines', 'underwriting rule engines'],
  },
  telecom: {
    products: [
      'builds network analytics and OSS/BSS software for telecom operators',
      'provides a cloud communications (SMS, voice and WhatsApp) API platform',
    ],
    focus: ['messaging delivery pipelines', 'usage-based billing systems', 'network telemetry ingestion', 'subscriber management services'],
  },
  travel: {
    products: [
      'runs an online bus and hotel booking platform',
      'builds corporate travel and expense management software',
    ],
    focus: ['inventory and availability search', 'booking and cancellation flows', 'dynamic pricing services', 'expense reconciliation'],
  },
  climate: {
    products: [
      'builds energy-monitoring software for commercial buildings and solar farms',
      'offers an EV charging network management platform',
    ],
    focus: ['IoT telemetry ingestion', 'energy forecasting models', 'charging session management', 'carbon reporting dashboards'],
  },
  media: {
    products: [
      'operates a regional-language OTT streaming service',
      'builds a creator-economy platform for podcasters and writers',
    ],
    focus: ['video transcoding pipelines', 'content recommendation', 'subscription paywalls', 'creator analytics'],
  },
};

// 40 fictional companies, each tied to a domain.
const COMPANIES = [
  ['Nimbus Ledger Technologies', 'fintech'], ['Quartzline Systems', 'saas'], ['Tealbridge Labs', 'healthtech'],
  ['Saffronwave Commerce', 'ecommerce'], ['Orbitrail Logistics Tech', 'logistics'], ['Brightpath Learning Labs', 'edtech'],
  ['Pixelforge Games Studio', 'gaming'], ['Suraksha Digital Insurance Tech', 'insurance'], ['Vayulink Networks', 'telecom'],
  ['Yatrigo Travel Tech', 'travel'], ['Greengrid Energy Software', 'climate'], ['Kathaverse Media', 'media'],
  ['Copperleaf Fintech', 'fintech'], ['Ardent Cloudworks', 'saas'], ['Medisphere Health Systems', 'healthtech'],
  ['Bazaarly Retail Tech', 'ecommerce'], ['Freightnova Solutions', 'logistics'], ['Vidyanext Education', 'edtech'],
  ['Lumen Arcade Interactive', 'gaming'], ['Kavach Assure Labs', 'insurance'], ['Signalhive Communications', 'telecom'],
  ['Wanderloop Technologies', 'travel'], ['Solstice Grid Analytics', 'climate'], ['Reelcraft Streaming', 'media'],
  ['Paisaflow Technologies', 'fintech'], ['Stackmint Software', 'saas'], ['Carevista Digital', 'healthtech'],
  ['Kartwheel Commerce Labs', 'ecommerce'], ['Routeloom Technologies', 'logistics'], ['Gyanpeak Learning', 'edtech'],
  ['Emberleaf Analytics', 'saas'], ['Ledgerbloom Finance Tech', 'fintech'], ['Aushadh Health Tech', 'healthtech'],
  ['Cartsy Marketplace Labs', 'ecommerce'], ['Voltbay Mobility Software', 'climate'], ['Northwind Pixel Labs', 'saas'],
  ['Indigo Harbor Systems', 'logistics'], ['Crestpoint Insurtech', 'insurance'], ['Tarang Telecom Software', 'telecom'],
  ['Mosaic Mint Technologies', 'fintech'],
];

const BENEFITS = [
  'Health insurance covering self, spouse, children and parents', 'Employee stock options (ESOPs)',
  'Annual learning budget of INR 50,000 for courses and conferences', 'Flexible working hours',
  'Home-office setup stipend', 'Annual company offsite', '26 weeks paid parental leave',
  'Daily meal allowance', 'Gym and wellness membership', 'Performance bonus paid twice a year',
  'Term life and accident insurance', 'Paid certification exams (AWS, Azure, Kubernetes, etc.)',
  'Internet reimbursement for remote days', 'Quarterly hackathons with cash prizes',
  'Mental health support through a counselling partner', 'Relocation assistance',
  'Company-provided MacBook Pro', '5-day work week with no-meeting Fridays', 'Sabbatical after 4 years of service',
];

const LEVELS = {
  intern: { bands: [[0, 1]], sal: [180000, 480000] },
  junior: { bands: [[0, 2], [1, 3]], sal: [400000, 900000] },
  mid: { bands: [[2, 4], [3, 5], [3, 6], [2, 5]], sal: [800000, 1800000] },
  senior: { bands: [[4, 7], [5, 8], [4, 8], [6, 10]], sal: [1400000, 3200000] },
  lead: { bands: [[7, 12], [8, 12], [6, 10]], sal: [2500000, 4500000] },
  architect: { bands: [[10, 15], [12, 15]], sal: [3500000, 6000000] },
};

function salaryFor(level, band) {
  const [lo, hi] = LEVELS[level].sal;
  if (level === 'intern') {
    const min = round50k(int(180000, 300000));
    return [min, Math.min(480000, round50k(min + int(100000, 180000)))];
  }
  // Senior roles with a lower 3-5 band sit at the bottom of the senior range.
  const span = hi - lo;
  const lowBand = level === 'senior' && band[0] <= 3;
  const minLo = lowBand ? 1200000 : lo;
  const minHi = lowBand ? 1600000 : lo + span * 0.45;
  const min = round50k(minLo + rand() * (minHi - minLo));
  const spread = round50k(span * (0.3 + rand() * 0.35));
  const max = Math.min(lowBand ? 2200000 : hi, Math.max(min + 200000, round50k(min + spread)));
  return [min, max];
}

// ---------------------------------------------------------------- role families
const FAMILIES = {
  java: {
    area: 'backend Java development',
    core: ['Java', 'Spring Boot'],
    pool: ['Microservices', 'Hibernate', 'Kafka', 'PostgreSQL', 'MySQL', 'Redis', 'Docker', 'Kubernetes', 'REST APIs', 'JUnit', 'Spring Cloud', 'Git', 'CI/CD'],
    titles: {
      intern: ['Java Developer Intern'],
      junior: ['Junior Java Developer', 'Associate Software Engineer - Java'],
      mid: ['Java Developer', 'Software Engineer - Java Backend', 'Java Spring Boot Developer'],
      senior: ['Senior Java Developer', 'Senior Backend Engineer (Java)', 'Senior Spring Boot Engineer'],
      lead: ['Lead Java Engineer', 'Java Tech Lead'],
      architect: ['Java Solutions Architect', 'Principal Engineer - Java Platforms'],
    },
    work: [
      'design and build high-throughput Spring Boot services that power {focus}',
      'own Java microservices behind {focus}, from API design to production monitoring',
      'modernise legacy Java modules into cloud-ready microservices for {focus}',
      'write clean, well-tested Java code for {focus} and help shape the service architecture',
    ],
    resp: [
      'Design, develop and maintain RESTful microservices using Java and Spring Boot',
      'Write unit and integration tests and keep code coverage healthy',
      'Participate in design reviews and contribute to technical documentation',
      'Optimise database queries and service performance under peak load',
      'Collaborate with product managers and frontend engineers on API contracts',
      'Troubleshoot production incidents and drive root-cause analysis',
      'Review pull requests and mentor less experienced engineers',
      'Improve observability with meaningful logs, metrics and alerts',
    ],
  },
  react: {
    area: 'frontend development with React',
    core: ['React', 'JavaScript', 'TypeScript'],
    pool: ['Redux', 'Next.js', 'HTML', 'CSS', 'Tailwind CSS', 'GraphQL', 'REST APIs', 'Cypress', 'Git', 'Figma'],
    titles: {
      intern: ['Frontend Developer Intern'],
      junior: ['Junior React Developer', 'Associate Frontend Engineer'],
      mid: ['React Developer', 'Frontend Engineer (React)', 'UI Engineer - React'],
      senior: ['Senior React Developer', 'Senior Frontend Engineer', 'Senior UI Engineer (React)'],
      lead: ['Frontend Tech Lead', 'Lead UI Engineer'],
      architect: ['Frontend Architect'],
    },
    work: [
      'build fast, accessible React interfaces for {focus}',
      'own the web experience for {focus}, from component design to performance tuning',
      'create reusable React components and a design-system foundation used across {focus}',
      'turn Figma designs into polished, responsive screens for {focus}',
    ],
    resp: [
      'Build responsive, accessible user interfaces with React and TypeScript',
      'Develop and maintain a shared component library',
      'Integrate REST and GraphQL APIs with robust state management',
      'Improve Core Web Vitals and bundle size across the application',
      'Write component and end-to-end tests',
      'Work closely with designers to refine interaction details',
      'Participate in code reviews and frontend architecture discussions',
    ],
  },
  fullstack: {
    area: 'full-stack web development',
    core: ['JavaScript', 'TypeScript'],
    pool: ['PostgreSQL', 'MongoDB', 'Docker', 'REST APIs', 'GraphQL', 'Git', 'AWS', 'Redis', 'CI/CD'],
    variants: [
      { core: ['React', 'Node.js', 'Express'], label: 'React/Node' },
      { core: ['React', 'Java', 'Spring Boot'], label: 'React/Java' },
      { core: ['Angular', 'C#', '.NET'], label: 'Angular/.NET' },
      { core: ['React', 'Node.js', 'NestJS'], label: 'React/Node' },
      { core: ['Next.js', 'React', 'Node.js'], label: 'React/Node' },
    ],
    titles: {
      junior: ['Junior Full Stack Developer'],
      mid: ['Full Stack Developer', 'Full Stack Engineer ({label})', 'Software Engineer - Full Stack'],
      senior: ['Senior Full Stack Developer', 'Senior Full Stack Engineer ({label})'],
      lead: ['Lead Full Stack Engineer'],
    },
    work: [
      'ship end-to-end features for {focus}, from database schema to UI',
      'own full-stack product slices of {focus} and iterate quickly with users',
      'build APIs and web screens that support {focus}',
    ],
    resp: [
      'Build features end to end across the frontend, backend and database',
      'Design clean REST or GraphQL APIs consumed by web clients',
      'Write automated tests across the stack',
      'Deploy and monitor services in cloud environments',
      'Collaborate with product and design to scope and deliver features',
      'Refactor and improve legacy parts of the codebase',
    ],
  },
  node: {
    area: 'backend development with Node.js',
    core: ['Node.js', 'TypeScript'],
    pool: ['Express', 'NestJS', 'MongoDB', 'PostgreSQL', 'Redis', 'Kafka', 'Docker', 'AWS', 'REST APIs', 'GraphQL', 'Git'],
    titles: {
      junior: ['Junior Node.js Developer'],
      mid: ['Node.js Developer', 'Backend Engineer (Node.js)'],
      senior: ['Senior Node.js Engineer', 'Senior Backend Engineer (Node.js)'],
      lead: ['Lead Backend Engineer (Node.js)'],
    },
    work: [
      'build scalable Node.js services and APIs for {focus}',
      'own event-driven Node.js backends that run {focus}',
      'design APIs and background workers behind {focus}',
    ],
    resp: [
      'Develop REST and GraphQL APIs with Node.js and TypeScript',
      'Design data models for relational and document databases',
      'Build asynchronous workers and event consumers',
      'Monitor service health and resolve production issues',
      'Write unit and integration tests',
      'Contribute to backend architecture and coding standards',
    ],
  },
  python: {
    area: 'backend development with Python',
    core: ['Python'],
    pool: ['Django', 'FastAPI', 'Flask', 'PostgreSQL', 'Redis', 'Docker', 'AWS', 'REST APIs', 'MySQL', 'Git', 'Linux'],
    titles: {
      intern: ['Python Developer Intern'],
      junior: ['Junior Python Developer'],
      mid: ['Python Developer', 'Backend Engineer (Python)', 'Django Developer'],
      senior: ['Senior Python Developer', 'Senior Backend Engineer (Python)'],
      lead: ['Lead Python Engineer'],
    },
    work: [
      'build reliable Python services with Django and FastAPI for {focus}',
      'develop APIs and data-heavy backend jobs for {focus}',
      'own Python microservices behind {focus}',
    ],
    resp: [
      'Build and maintain backend services in Python',
      'Design REST APIs and background task pipelines',
      'Write well-tested, readable code with type hints',
      'Optimise SQL queries and caching strategies',
      'Deploy services with Docker and CI/CD pipelines',
      'Participate in on-call rotation and incident reviews',
    ],
  },
  devops: {
    area: 'DevOps, cloud infrastructure or SRE',
    core: ['Kubernetes', 'Docker'],
    pool: ['Terraform', 'Jenkins', 'GitHub Actions', 'Linux', 'Prometheus', 'Grafana', 'CI/CD', 'Python', 'Go', 'Networking', 'Git'],
    titles: {
      junior: ['Junior DevOps Engineer'],
      mid: ['DevOps Engineer', 'Cloud Engineer', 'Site Reliability Engineer'],
      senior: ['Senior DevOps Engineer', 'Senior Site Reliability Engineer', 'Senior Cloud Engineer (AWS)'],
      lead: ['Lead SRE', 'DevOps Lead'],
      architect: ['Cloud Solutions Architect'],
    },
    work: [
      'automate and operate the cloud infrastructure that runs {focus}',
      'improve reliability, deployment speed and cost efficiency for {focus}',
      'build self-service CI/CD and Kubernetes platforms used by teams working on {focus}',
    ],
    resp: [
      'Manage infrastructure as code with Terraform',
      'Operate and scale Kubernetes clusters',
      'Build and maintain CI/CD pipelines',
      'Define SLOs and improve monitoring and alerting',
      'Lead incident response and blameless post-mortems',
      'Optimise cloud cost and security posture',
      'Automate routine operations with scripts and tooling',
    ],
  },
  data: {
    area: 'data engineering',
    core: ['Python', 'SQL', 'Spark'],
    pool: ['Airflow', 'Snowflake', 'dbt', 'Kafka', 'AWS', 'GCP', 'Scala', 'PostgreSQL', 'Docker', 'Git'],
    titles: {
      junior: ['Junior Data Engineer'],
      mid: ['Data Engineer', 'Big Data Engineer'],
      senior: ['Senior Data Engineer', 'Senior Analytics Engineer'],
      lead: ['Lead Data Engineer'],
      architect: ['Data Platform Architect'],
    },
    work: [
      'build batch and streaming data pipelines that feed {focus}',
      'own the data warehouse and pipeline reliability for {focus}',
      'model and deliver trustworthy datasets used for {focus}',
    ],
    resp: [
      'Build batch and streaming pipelines with Spark and Airflow',
      'Model data in the warehouse for analytics and reporting',
      'Ensure data quality with tests, monitoring and lineage',
      'Optimise pipeline performance and storage costs',
      'Partner with analysts and data scientists on data needs',
      'Document datasets and pipeline ownership',
    ],
  },
  ml: {
    area: 'machine learning or data science',
    core: ['Python', 'Machine Learning'],
    pool: ['PyTorch', 'TensorFlow', 'NLP', 'LLMs', 'Deep Learning', 'Pandas', 'NumPy', 'SQL', 'AWS', 'Docker', 'FastAPI'],
    titles: {
      intern: ['Machine Learning Intern'],
      junior: ['Junior Data Scientist'],
      mid: ['Data Scientist', 'Machine Learning Engineer', 'NLP Engineer'],
      senior: ['Senior Machine Learning Engineer', 'Senior Data Scientist', 'Senior GenAI Engineer'],
      lead: ['Lead Data Scientist', 'Staff ML Engineer'],
    },
    work: [
      'build and deploy machine learning models that improve {focus}',
      'develop NLP and LLM-powered features for {focus}',
      'turn data into production models and experiments for {focus}',
    ],
    resp: [
      'Develop, train and evaluate machine learning models',
      'Build retrieval-augmented and LLM-based features where appropriate',
      'Deploy models as APIs and monitor drift',
      'Design experiments and communicate results to stakeholders',
      'Clean and explore large datasets',
      'Collaborate with engineers to productionise models',
    ],
  },
  mobile: {
    area: 'mobile app development',
    core: [],
    pool: ['Git', 'REST APIs', 'CI/CD', 'GraphQL', 'Agile', 'Figma'],
    variants: [
      { core: ['Kotlin', 'Android'], label: 'Android', t: 'Android' },
      { core: ['Swift', 'iOS'], label: 'iOS', t: 'iOS' },
      { core: ['Flutter'], label: 'Flutter', t: 'Flutter' },
      { core: ['React Native', 'JavaScript', 'TypeScript'], label: 'React Native', t: 'React Native' },
    ],
    titles: {
      junior: ['Junior {t} Developer'],
      mid: ['{t} Developer', 'Mobile Engineer ({t})'],
      senior: ['Senior {t} Developer', 'Senior Mobile Engineer ({t})'],
      lead: ['Mobile Tech Lead ({t})'],
    },
    work: [
      'build smooth, reliable mobile experiences for {focus}',
      'own the mobile app features behind {focus}',
      'improve app performance, stability and release cadence for {focus}',
    ],
    resp: [
      'Build and ship new mobile app features',
      'Maintain high crash-free session rates and app performance',
      'Integrate REST/GraphQL APIs and offline support',
      'Write unit and UI tests',
      'Manage app store releases and feature flags',
      'Collaborate with designers on interaction details',
    ],
  },
  qa: {
    area: 'test automation',
    core: [],
    pool: ['Java', 'JavaScript', 'TypeScript', 'Python', 'JUnit', 'CI/CD', 'Jenkins', 'Git', 'REST APIs', 'Agile'],
    variants: [
      { core: ['Selenium', 'Java'], label: 'Selenium' },
      { core: ['Cypress', 'JavaScript'], label: 'Cypress' },
      { core: ['Playwright', 'TypeScript'], label: 'Playwright' },
    ],
    titles: {
      junior: ['QA Automation Engineer'],
      mid: ['QA Automation Engineer', 'SDET'],
      senior: ['Senior SDET', 'Senior QA Automation Engineer'],
      lead: ['QA Lead'],
    },
    work: [
      'build automated test suites that protect releases of {focus}',
      'own test strategy and automation frameworks for {focus}',
    ],
    resp: [
      'Design and maintain automated UI and API test suites',
      'Integrate tests into CI/CD pipelines',
      'Define test plans and acceptance criteria with product teams',
      'Track and triage defects with developers',
      'Improve test reliability and reduce flaky tests',
    ],
  },
  systems: {
    area: 'backend systems development',
    core: [],
    pool: ['Docker', 'Kubernetes', 'PostgreSQL', 'Redis', 'Linux', 'Git', 'Microservices', 'Kafka', 'GCP', 'Azure'],
    variants: [
      { core: ['Go', 'Microservices'], label: 'Go', t: 'Go' },
      { core: ['Rust', 'Linux'], label: 'Rust', t: 'Rust' },
      { core: ['C#', '.NET', 'Azure'], label: '.NET', t: '.NET' },
    ],
    titles: {
      mid: ['{t} Developer', 'Backend Engineer ({t})'],
      senior: ['Senior {t} Engineer', 'Senior Backend Engineer ({t})'],
      lead: ['Lead {t} Engineer'],
    },
    work: [
      'build low-latency backend services for {focus}',
      'design performance-critical systems that power {focus}',
    ],
    resp: [
      'Design and build performant backend services',
      'Profile and optimise latency-critical code paths',
      'Write thorough tests and benchmarks',
      'Contribute to system design and technical roadmaps',
      'Operate services in production and handle incidents',
    ],
  },
  other: {
    area: 'the relevant discipline',
    core: [],
    pool: ['Agile', 'Git', 'SQL'],
    variants: [
      { core: ['Figma', 'HTML', 'CSS'], label: 'Design', title: { mid: 'UI/UX Designer', senior: 'Senior Product Designer' }, area: 'product and UI/UX design' },
      { core: ['Cybersecurity', 'Networking', 'Linux', 'AWS'], label: 'Security', title: { mid: 'Security Engineer', senior: 'Senior Application Security Engineer' }, area: 'information security' },
      { core: ['Salesforce', 'JavaScript', 'REST APIs'], label: 'Salesforce', title: { mid: 'Salesforce Developer', senior: 'Senior Salesforce Developer' }, area: 'Salesforce development' },
      { core: ['SQL', 'Power BI', 'Tableau', 'Python'], label: 'Analyst', title: { junior: 'Data Analyst', mid: 'Data Analyst', senior: 'Senior Business Intelligence Analyst' }, area: 'data analysis and BI' },
      { core: ['SAP', 'SQL'], label: 'SAP', title: { mid: 'SAP ABAP Consultant', senior: 'Senior SAP Consultant' }, area: 'SAP implementation' },
    ],
    titles: {},
    work: [
      'help teams working on {focus} ship better outcomes',
      'own key initiatives that support {focus}',
    ],
    resp: [
      'Partner with cross-functional teams to deliver outcomes',
      'Document processes and share knowledge with the team',
      'Present findings and recommendations to stakeholders',
      'Continuously improve tooling and ways of working',
      'Keep up with best practices in the field',
    ],
  },
};

const VARIANT_RESP = {
  Design: ['Run user research and usability tests', 'Create wireframes, prototypes and high-fidelity designs in Figma', 'Maintain the design system'],
  Security: ['Perform threat modelling and security reviews', 'Run vulnerability assessments and coordinate remediation', 'Harden cloud and network configurations'],
  Salesforce: ['Build Apex classes, triggers and Lightning components', 'Integrate Salesforce with internal systems via REST APIs', 'Maintain CRM data quality'],
  Analyst: ['Build Power BI and Tableau dashboards for business teams', 'Write SQL to answer ad-hoc business questions', 'Define and track key business metrics'],
  SAP: ['Develop ABAP reports and enhancements', 'Support SAP S/4HANA finance and logistics modules', 'Gather requirements from business users'],
};

const SKILL_REQ = {
  Java: 'Strong command of core Java (collections, concurrency, streams)',
  'Spring Boot': 'Hands-on experience building REST services with Spring Boot',
  'Spring Cloud': 'Familiarity with Spring Cloud components (config, gateway, discovery)',
  Hibernate: 'Experience with JPA/Hibernate and relational data modelling',
  Microservices: 'Experience designing and operating microservices in production',
  Kafka: 'Experience with event streaming using Kafka',
  AWS: 'Hands-on experience with AWS services such as EC2, S3, RDS and Lambda',
  Azure: 'Working experience with Azure services such as App Service, AKS and Azure SQL',
  GCP: 'Experience with GCP services such as GKE, BigQuery and Pub/Sub',
  Docker: 'Comfort with containerised development and deployment using Docker',
  Kubernetes: 'Experience deploying and operating workloads on Kubernetes',
  Terraform: 'Infrastructure-as-code experience with Terraform',
  PostgreSQL: 'Good SQL skills and experience with PostgreSQL',
  MySQL: 'Experience with MySQL schema design and query tuning',
  MongoDB: 'Experience with MongoDB data modelling',
  Redis: 'Experience using Redis for caching or queues',
  React: 'Strong experience building production applications with React',
  TypeScript: 'Solid TypeScript skills',
  JavaScript: 'Deep understanding of modern JavaScript (ES2020+)',
  Redux: 'Experience with Redux or similar state management',
  'Next.js': 'Experience with Next.js and server-side rendering',
  Angular: 'Experience building applications with Angular',
  'Node.js': 'Strong experience with Node.js backend development',
  NestJS: 'Experience with NestJS',
  Express: 'Experience building APIs with Express',
  Python: 'Strong Python programming skills',
  Django: 'Experience with Django and Django REST Framework',
  FastAPI: 'Experience building async APIs with FastAPI',
  Spark: 'Experience building data pipelines with Apache Spark',
  Airflow: 'Experience orchestrating workflows with Airflow',
  Snowflake: 'Experience with Snowflake or a similar cloud data warehouse',
  SQL: 'Advanced SQL skills',
  dbt: 'Experience with dbt for data modelling',
  'Machine Learning': 'Solid understanding of machine learning fundamentals and model evaluation',
  PyTorch: 'Hands-on experience with PyTorch',
  TensorFlow: 'Hands-on experience with TensorFlow',
  NLP: 'Experience with NLP techniques and transformer models',
  LLMs: 'Experience building applications with large language models (prompting, RAG, evaluation)',
  Kotlin: 'Strong Kotlin skills for Android development',
  Android: 'Experience shipping Android apps with Jetpack libraries',
  Swift: 'Strong Swift skills',
  iOS: 'Experience shipping iOS apps with UIKit or SwiftUI',
  Flutter: 'Experience building cross-platform apps with Flutter and Dart',
  'React Native': 'Experience building apps with React Native',
  Selenium: 'Experience with Selenium WebDriver test automation',
  Cypress: 'Experience writing end-to-end tests with Cypress',
  Playwright: 'Experience writing end-to-end tests with Playwright',
  Go: 'Strong Go programming skills',
  Rust: 'Strong Rust programming skills',
  'C#': 'Strong C# skills',
  '.NET': 'Experience with .NET (ASP.NET Core)',
  Figma: 'Strong portfolio of work designed in Figma',
  Cybersecurity: 'Knowledge of OWASP Top 10 and common attack techniques',
  Networking: 'Good understanding of TCP/IP, DNS and network security',
  Salesforce: 'Salesforce Platform Developer certification or equivalent experience',
  'Power BI': 'Experience building dashboards in Power BI',
  Tableau: 'Experience building dashboards in Tableau',
  SAP: 'Experience with SAP ABAP or S/4HANA modules',
  Linux: 'Comfort working on Linux systems',
  Jenkins: 'Experience with Jenkins pipelines',
  'GitHub Actions': 'Experience with GitHub Actions workflows',
  Prometheus: 'Experience with Prometheus-based monitoring',
  GraphQL: 'Experience designing or consuming GraphQL APIs',
};

const SOFT_REQ = [
  'Good communication skills and comfort working with distributed teams',
  'Ability to break down ambiguous problems and deliver incrementally',
  'Bachelor’s degree in Computer Science or equivalent practical experience',
  'A strong sense of ownership and attention to detail',
  'Experience working in Agile/Scrum teams',
];

const TEAM_SENTENCES = [
  'You will join a small, product-focused team that ships to production several times a week.',
  'The engineering team values code reviews, pairing and clear written communication.',
  'You will work closely with product managers, designers and customer-facing teams.',
  'The team is growing quickly and there is plenty of room to shape its practices.',
  'You will have real ownership of your services and the metrics that matter to them.',
  'The company runs lean, cross-functional squads with a strong culture of documentation.',
];

const CITIES = ['Bangalore', 'Hyderabad', 'Pune', 'Chennai', 'Mumbai', 'Gurgaon', 'Noida', 'Ahmedabad', 'Kochi', 'Kolkata'];

// ---------------------------------------------------------------- job specs
// S(family, level, loc, opts) — loc is a city or 'Remote'. opts: r (remote-friendly), band, add, type, v (variant index)
const S = (family, level, loc, opts = {}) => ({ family, level, loc, ...opts });

const SPECS = [
  // Java backend (24) — 9 remote
  S('java', 'junior', 'Pune'),
  S('java', 'junior', 'Hyderabad'),
  S('java', 'intern', 'Bangalore'),
  S('java', 'mid', 'Bangalore', { r: 1, add: ['AWS'] }),
  S('java', 'mid', 'Pune', { add: ['AWS'] }),
  S('java', 'mid', 'Remote', { add: ['AWS'], band: [3, 5] }),
  S('java', 'mid', 'Chennai'),
  S('java', 'mid', 'Hyderabad', { r: 1, band: [2, 4] }),
  S('java', 'senior', 'Bangalore', { r: 1, add: ['AWS'], band: [3, 5] }),
  S('java', 'senior', 'Remote', { add: ['AWS'], band: [3, 5] }),
  S('java', 'senior', 'Pune', { add: ['AWS'], band: [4, 7] }),
  S('java', 'senior', 'Remote', { add: ['AWS', 'Kafka'], band: [4, 7] }),
  S('java', 'senior', 'Hyderabad', { add: ['AWS'], band: [4, 7] }),
  S('java', 'senior', 'Bangalore', { add: ['AWS'], band: [5, 8] }),
  S('java', 'senior', 'Gurgaon', { r: 1, band: [5, 8] }),
  S('java', 'senior', 'Noida', { add: ['Azure'], band: [4, 7] }),
  S('java', 'lead', 'Bangalore', { add: ['AWS'] }),
  S('java', 'lead', 'Remote', { add: ['AWS'] }),
  S('java', 'lead', 'Pune'),
  S('java', 'architect', 'Bangalore', { add: ['AWS'] }),
  S('java', 'architect', 'Hyderabad', { r: 1, add: ['AWS'] }),
  S('java', 'mid', 'Mumbai', { add: ['AWS'], type: 'CONTRACT' }),
  S('java', 'mid', 'Kochi'),
  S('java', 'senior', 'Ahmedabad', { band: [3, 5] }),

  // React / frontend (17) — 6 Bangalore, 5 remote
  S('react', 'intern', 'Bangalore'),
  S('react', 'junior', 'Pune'),
  S('react', 'junior', 'Bangalore'),
  S('react', 'mid', 'Bangalore', { band: [3, 5] }),
  S('react', 'mid', 'Remote', { band: [3, 5] }),
  S('react', 'mid', 'Hyderabad', { band: [2, 4] }),
  S('react', 'mid', 'Chennai', { r: 1, band: [3, 6] }),
  S('react', 'mid', 'Bangalore', { r: 1, band: [2, 5] }),
  S('react', 'senior', 'Bangalore', { band: [4, 7] }),
  S('react', 'senior', 'Remote', { band: [4, 7] }),
  S('react', 'senior', 'Gurgaon', { band: [5, 8] }),
  S('react', 'senior', 'Mumbai', { band: [3, 5] }),
  S('react', 'lead', 'Bangalore'),
  S('react', 'lead', 'Remote'),
  S('react', 'mid', 'Noida', { type: 'CONTRACT' }),
  S('react', 'architect', 'Hyderabad'),
  S('react', 'mid', 'Kolkata', { type: 'PART_TIME', band: [2, 4] }),

  // Full-stack (11) — 4 remote
  S('fullstack', 'junior', 'Ahmedabad', { v: 0 }),
  S('fullstack', 'mid', 'Bangalore', { v: 0, r: 1 }),
  S('fullstack', 'mid', 'Pune', { v: 1, band: [3, 5] }),
  S('fullstack', 'mid', 'Hyderabad', { v: 2 }),
  S('fullstack', 'mid', 'Remote', { v: 3 }),
  S('fullstack', 'senior', 'Bangalore', { v: 1, add: ['AWS'] }),
  S('fullstack', 'senior', 'Remote', { v: 4, add: ['AWS'] }),
  S('fullstack', 'senior', 'Noida', { v: 2 }),
  S('fullstack', 'senior', 'Chennai', { v: 0, type: 'CONTRACT' }),
  S('fullstack', 'lead', 'Gurgaon', { v: 1, r: 1 }),
  S('fullstack', 'mid', 'Kochi', { v: 2 }),

  // Node.js (6) — 3 remote
  S('node', 'junior', 'Bangalore'),
  S('node', 'mid', 'Remote'),
  S('node', 'mid', 'Mumbai', { add: ['AWS'] }),
  S('node', 'senior', 'Remote', { add: ['AWS'] }),
  S('node', 'senior', 'Hyderabad', { r: 1 }),
  S('node', 'lead', 'Bangalore', { add: ['AWS'] }),

  // Python (7) — 2 remote
  S('python', 'intern', 'Pune'),
  S('python', 'junior', 'Chennai'),
  S('python', 'mid', 'Bangalore', { add: ['Django'] }),
  S('python', 'mid', 'Remote', { add: ['FastAPI', 'AWS'] }),
  S('python', 'senior', 'Hyderabad', { add: ['FastAPI'] }),
  S('python', 'senior', 'Gurgaon', { add: ['Django', 'AWS'], r: 1 }),
  S('python', 'lead', 'Pune', { add: ['Django'] }),

  // DevOps / Cloud / SRE (12) — 7 remote, many AWS
  S('devops', 'junior', 'Noida', { add: ['AWS'] }),
  S('devops', 'mid', 'Remote', { add: ['AWS', 'Terraform'] }),
  S('devops', 'mid', 'Bangalore', { add: ['AWS'], r: 1 }),
  S('devops', 'mid', 'Pune', { add: ['Azure'] }),
  S('devops', 'mid', 'Remote', { add: ['GCP', 'Terraform'] }),
  S('devops', 'senior', 'Remote', { add: ['AWS', 'Terraform'] }),
  S('devops', 'senior', 'Hyderabad', { add: ['AWS'] }),
  S('devops', 'senior', 'Remote', { add: ['AWS', 'Prometheus'] }),
  S('devops', 'senior', 'Chennai', { add: ['Azure', 'Terraform'], type: 'CONTRACT' }),
  S('devops', 'lead', 'Remote', { add: ['AWS', 'Terraform'] }),
  S('devops', 'lead', 'Bangalore', { add: ['AWS'] }),
  S('devops', 'architect', 'Gurgaon', { add: ['AWS', 'Terraform'], r: 1 }),

  // Data engineering (8) — 3 remote
  S('data', 'junior', 'Kolkata'),
  S('data', 'mid', 'Bangalore', { add: ['Airflow', 'Snowflake'] }),
  S('data', 'mid', 'Remote', { add: ['AWS', 'Airflow'] }),
  S('data', 'mid', 'Hyderabad', { add: ['GCP'] }),
  S('data', 'senior', 'Pune', { add: ['Snowflake', 'dbt'] }),
  S('data', 'senior', 'Remote', { add: ['AWS', 'Kafka'] }),
  S('data', 'lead', 'Bangalore', { add: ['Airflow'], r: 1 }),
  S('data', 'architect', 'Mumbai', { add: ['Snowflake', 'AWS'] }),

  // ML / DS / GenAI (8) — 3 remote
  S('ml', 'intern', 'Bangalore', { type: 'INTERNSHIP' }),
  S('ml', 'junior', 'Hyderabad', { add: ['Pandas'] }),
  S('ml', 'mid', 'Bangalore', { add: ['NLP', 'PyTorch'] }),
  S('ml', 'mid', 'Remote', { add: ['LLMs', 'NLP'] }),
  S('ml', 'senior', 'Pune', { add: ['PyTorch', 'Deep Learning'] }),
  S('ml', 'senior', 'Remote', { add: ['LLMs', 'AWS'] }),
  S('ml', 'senior', 'Gurgaon', { add: ['TensorFlow'], r: 1 }),
  S('ml', 'lead', 'Bangalore', { add: ['LLMs', 'PyTorch'] }),

  // Mobile (8) — 3 remote
  S('mobile', 'junior', 'Chennai', { v: 0 }),
  S('mobile', 'mid', 'Bangalore', { v: 0 }),
  S('mobile', 'mid', 'Remote', { v: 2 }),
  S('mobile', 'mid', 'Mumbai', { v: 1 }),
  S('mobile', 'senior', 'Bangalore', { v: 1, r: 1 }),
  S('mobile', 'senior', 'Remote', { v: 3 }),
  S('mobile', 'senior', 'Hyderabad', { v: 0 }),
  S('mobile', 'lead', 'Pune', { v: 2, type: 'CONTRACT' }),

  // QA automation (6) — 2 remote
  S('qa', 'junior', 'Noida', { v: 0 }),
  S('qa', 'mid', 'Pune', { v: 0 }),
  S('qa', 'mid', 'Remote', { v: 1 }),
  S('qa', 'mid', 'Kochi', { v: 2, type: 'PART_TIME' }),
  S('qa', 'senior', 'Bangalore', { v: 2, r: 1 }),
  S('qa', 'lead', 'Hyderabad', { v: 0 }),

  // Go / Rust / .NET (6) — 2 remote
  S('systems', 'mid', 'Bangalore', { v: 0 }),
  S('systems', 'senior', 'Remote', { v: 0, add: ['AWS'] }),
  S('systems', 'senior', 'Pune', { v: 1 }),
  S('systems', 'mid', 'Hyderabad', { v: 2 }),
  S('systems', 'senior', 'Remote', { v: 2 }),
  S('systems', 'lead', 'Gurgaon', { v: 0, type: 'CONTRACT' }),

  // Other (7) — 2 remote
  S('other', 'mid', 'Bangalore', { v: 0 }),
  S('other', 'senior', 'Remote', { v: 0, band: [5, 8] }),
  S('other', 'senior', 'Hyderabad', { v: 1, band: [4, 7] }),
  S('other', 'mid', 'Pune', { v: 2 }),
  S('other', 'junior', 'Mumbai', { v: 3 }),
  S('other', 'mid', 'Remote', { v: 3, type: 'PART_TIME', band: [2, 4] }),
  S('other', 'senior', 'Chennai', { v: 4, type: 'CONTRACT', band: [5, 8] }),
];

// ---------------------------------------------------------------- generation
function fill(template, vars) {
  return template.replace(/\{(\w+)\}/g, (_, k) => vars[k] ?? '');
}

function isoDate(start, offsetDays) {
  const d = new Date(Date.UTC(...start));
  d.setUTCDate(d.getUTCDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

function buildJob(spec, id) {
  const fam = FAMILIES[spec.family];
  const variant = fam.variants ? fam.variants[spec.v ?? 0] : null;
  const level = spec.level;
  const band = spec.band ?? pick(LEVELS[level].bands);
  const [experienceMin, experienceMax] = level === 'intern' ? [0, 1] : band;
  const employmentType = level === 'intern' ? 'INTERNSHIP' : spec.type ?? 'FULL_TIME';
  const [salaryMin, salaryMax] = salaryFor(level, band);

  // Title
  let title;
  if (variant?.title) {
    title = variant.title[level] ?? variant.title.mid;
    if (level === 'senior' && !variant.title.senior) title = `Senior ${title}`;
  } else {
    const titleOptions = fam.titles[level] ?? fam.titles.mid;
    title = fill(pick(titleOptions), { label: variant?.label, t: variant?.t });
  }
  if (employmentType === 'CONTRACT') title += ' (Contract)';
  if (employmentType === 'PART_TIME') title += ' (Part-time)';

  // Skills: core + variant core + explicit adds + random pool picks, 4-8 total
  const skills = [];
  const push = (s) => { if (!skills.includes(s)) skills.push(s); };
  (variant?.core ?? []).forEach(push);
  fam.core.forEach(push);
  (spec.add ?? []).forEach(push);
  const target = Math.min(8, Math.max(4, skills.length + int(2, 3)));
  const pool = fam.pool.filter((s) => !skills.includes(s) && !(s === 'AWS' && (spec.add ?? []).some((a) => a === 'Azure' || a === 'GCP')));
  sample(pool, target - skills.length).forEach(push);
  skills.splice(8);

  // Company / domain
  const [company, domainKey] = pick(COMPANIES);
  const domain = DOMAINS[domainKey];
  const focus = pick(domain.focus);

  // Location / remote
  const remote = spec.loc === 'Remote' || Boolean(spec.r);
  const location = spec.loc === 'Remote' ? 'Remote, India' : `${spec.loc}, India`;

  // Description
  const sentences = [
    `${company} ${pick(domain.products)}.`,
    `As a ${title.replace(/ \((Contract|Part-time)\)$/, '')}, you will ${fill(pick(fam.work), { focus })}.`,
  ];
  if (variant?.area && spec.family === 'other') {
    sentences[1] = `As a ${title.replace(/ \((Contract|Part-time)\)$/, '')}, you will focus on ${variant.area} and ${fill(pick(fam.work), { focus })}.`;
  }
  if (level === 'intern') sentences.push('This six-month internship includes mentorship from a senior engineer and a possible pre-placement offer.');
  else if (employmentType === 'CONTRACT') sentences.push(`This is a ${pick([6, 9, 12])}-month contract engagement with a possibility of extension.`);
  else if (employmentType === 'PART_TIME') sentences.push('This is a part-time role of roughly 20-25 hours per week with flexible scheduling.');
  else sentences.push(pick(TEAM_SENTENCES));
  if (spec.loc === 'Remote') sentences.push('This role is fully remote for candidates based anywhere in India.');
  else if (remote) sentences.push(`The role is remote-friendly, with an optional office hub in ${spec.loc}.`);

  // Requirements
  const primary = variant?.area ?? fam.area;
  const requirements = [];
  if (level === 'intern') requirements.push('Final-year student or recent graduate in Computer Science or a related field');
  else if (experienceMin === 0) requirements.push(`0-${experienceMax} years of experience in ${primary}, including internships or substantial projects`);
  else requirements.push(`${experienceMin}+ years of professional experience in ${primary}`);
  const skillReqs = skills.filter((s) => SKILL_REQ[s]).map((s) => SKILL_REQ[s]);
  const nSkillReqs = Math.min(skillReqs.length, int(2, 4));
  requirements.push(...skillReqs.slice(0, nSkillReqs));
  if (['lead', 'architect'].includes(level)) requirements.push('Proven experience leading technical design and mentoring engineers');
  requirements.push(pick(SOFT_REQ));
  while (requirements.length < 4) requirements.push(`Practical experience with ${skills[requirements.length % skills.length]}`);
  requirements.splice(6);

  // Responsibilities
  const respPool = [...(variant && VARIANT_RESP[variant.label] ? VARIANT_RESP[variant.label] : []), ...fam.resp];
  const responsibilities = variant && VARIANT_RESP[variant.label]
    ? [...VARIANT_RESP[variant.label], ...sample(fam.resp, int(1, 3))]
    : sample(respPool, int(4, 6));
  if (['lead', 'architect'].includes(level)) responsibilities[responsibilities.length - 1] = 'Set technical direction and mentor engineers across the team';

  const benefits = sample(BENEFITS, int(3, 6));
  const postedDate = isoDate([2026, 6, 1], int(0, 85)); // 2026-07-01 .. 2026-09-24

  return {
    id,
    title,
    company,
    location,
    remote,
    employmentType,
    experienceMin,
    experienceMax,
    salaryMin,
    salaryMax,
    currency: 'INR',
    skills,
    description: sentences.join(' '),
    requirements,
    responsibilities,
    benefits,
    postedDate,
    applicationUrl: `https://example.com/demo-jobs/${id}`,
  };
}

const jobs = SPECS.map((spec, i) => ({ ...buildJob(spec, 101 + i), _family: spec.family }));

// ---------------------------------------------------------------- validation
const KEYS = ['id', 'title', 'company', 'location', 'remote', 'employmentType', 'experienceMin', 'experienceMax', 'salaryMin', 'salaryMax', 'currency', 'skills', 'description', 'requirements', 'responsibilities', 'benefits', 'postedDate', 'applicationUrl'];
const errors = [];
const ids = new Set();
for (const j of jobs) {
  if (ids.has(j.id)) errors.push(`duplicate id ${j.id}`);
  ids.add(j.id);
  for (const k of KEYS) if (!(k in j)) errors.push(`${j.id} missing ${k}`);
  if (!(j.salaryMin < j.salaryMax)) errors.push(`${j.id} salaryMin >= salaryMax`);
  if (j.salaryMin % 50000 || j.salaryMax % 50000) errors.push(`${j.id} salary not rounded`);
  if (j.location === 'Remote, India' && !j.remote) errors.push(`${j.id} remote inconsistency`);
  if (j.employmentType !== 'INTERNSHIP' && !(j.experienceMax > j.experienceMin)) errors.push(`${j.id} experience band`);
  if (j.skills.length < 4 || j.skills.length > 8) errors.push(`${j.id} skills count ${j.skills.length}`);
  for (const s of j.skills) if (!ALLOWED_SKILLS.includes(s)) errors.push(`${j.id} bad skill ${s}`);
  if (j.requirements.length < 4 || j.requirements.length > 6) errors.push(`${j.id} requirements count`);
  if (j.responsibilities.length < 4 || j.responsibilities.length > 6) errors.push(`${j.id} responsibilities count`);
  if (j.postedDate < '2026-07-01' || j.postedDate > '2026-09-24') errors.push(`${j.id} postedDate`);
}
if (jobs.length !== 120) errors.push(`expected 120 jobs, got ${jobs.length}`);
if (errors.length) {
  console.error('Validation failed:\n' + errors.join('\n'));
  process.exit(1);
}

// ---------------------------------------------------------------- stats + write
const families = {};
for (const j of jobs) families[j._family] = (families[j._family] ?? 0) + 1;
const has = (j, s) => j.skills.includes(s);
console.log('Jobs:', jobs.length, '| companies:', new Set(jobs.map((j) => j.company)).size);
console.log('Per family:', families);
console.log('Remote:', jobs.filter((j) => j.remote).length);
console.log('Employment types:', jobs.reduce((a, j) => ((a[j.employmentType] = (a[j.employmentType] ?? 0) + 1), a), {}));
console.log('Java+AWS+remote:', jobs.filter((j) => has(j, 'Java') && has(j, 'AWS') && j.remote).length);
console.log('Java+Spring Boot+remote:', jobs.filter((j) => has(j, 'Java') && has(j, 'Spring Boot') && j.remote).length);
console.log('React+Bangalore:', jobs.filter((j) => has(j, 'React') && j.location.startsWith('Bangalore')).length);
console.log('Remote+AWS:', jobs.filter((j) => has(j, 'AWS') && j.remote).length);
console.log('salaryMax >= 12 LPA:', jobs.filter((j) => j.salaryMax >= 1200000).length);

const output = {
  dataset: 'Job Search Assistant - demo job dataset',
  disclaimer: 'All jobs, companies, salaries and links in this file are fictional sample data created for demonstration purposes. They are not real or current vacancies.',
  version: 1,
  generatedAt: '2026-09-25',
  jobs: jobs.map(({ _family, ...j }) => j),
};
const outPath = join(dirname(fileURLToPath(import.meta.url)), '..', 'jobs.json');
writeFileSync(outPath, JSON.stringify(output, null, 2) + '\n', 'utf8');
console.log('Wrote', outPath);
