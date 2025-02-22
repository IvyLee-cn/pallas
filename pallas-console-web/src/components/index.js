import Vue from 'vue';
import Editor from './common/ace_editor';
import Container from './common/container';
import Navbar from './common/navbar';
import VersionFooter from './common/version_footer';
import ChartContainer from './common/charts/chart_container';
import Pie from './common/charts/pie';
import MyLine from './common/charts/line';
import Column from './common/charts/column';
import Timeline from './common/timeline';
import TimelineItem from './common/timeline_item';
import JsonContentDialog from './common/json_content';
import JsonDiff from './common/json_diff';
import Panel from './common/panel';
import IndexDataSourceItem from '../pages/index_manage/index_info_dialog/index_data_sources/index_data_source_item';

Vue.component('index-data-source-item', IndexDataSourceItem);
Vue.component('Container', Container);
Vue.component('Navbar', Navbar);
Vue.component('Editor', Editor);
Vue.component('Timeline', Timeline);
Vue.component('Timeline-item', TimelineItem);
Vue.component('Version-footer', VersionFooter);
Vue.component('chart-container', ChartContainer);
Vue.component('Pie', Pie);
Vue.component('Column', Column);
Vue.component('MyLine', MyLine);
Vue.component('json-content-dialog', JsonContentDialog);
Vue.component('json-diff', JsonDiff);
Vue.component('Panel', Panel);
Vue.component('log-monitor', { template: '<div></div>' });
