import React from 'react';
import {createMuiTheme, makeStyles, MuiThemeProvider} from '@material-ui/core/styles';
import {Redirect, Route, Switch} from 'react-router-dom';
import TopAppBar from "./TopAppBar";
import Overview from "../components/overview/overview";
import Servers from "../components/servers/servers";
import Jobs from '../components/jobs/jobs.js';
import Job from '../components/jobs/job.js';
import RecurringJobs from "../components/recurring-jobs/recurring-jobs";

const useStyles = makeStyles(theme => ({
    root: {
        flexGrow: 1,
    },
    content: {
        flexGrow: 1,
        padding: theme.spacing(3),
        marginTop: 56
    },
    paper: {
        padding: theme.spacing(2),
        textAlign: 'center',
        color: theme.palette.text.secondary,
    },
}));

const AdminUI = function () {
    const theme = createMuiTheme({
        palette: {
            primary: {
                main: '#000'
            }
        }
    });
    const classes = useStyles();

    return (
        <MuiThemeProvider theme={theme}>
            <div className={classes.root}>
                <TopAppBar/>
                <main className={classes.content}>
                    <Switch>
                        <Route path="/dashboard/overview" component={Overview}/>
                        <Route path="/dashboard/jobs/:id" component={Job}/>
                        <Route path="/dashboard/jobs" component={Jobs}/>
                        <Route path="/dashboard/recurring-jobs" component={RecurringJobs}/>
                        <Route path="/dashboard/servers" component={Servers}/>
                        <Redirect from="/dashboard" to="/dashboard/overview"/>
                    </Switch>
                </main>
            </div>
        </MuiThemeProvider>
    );
};

export default AdminUI;